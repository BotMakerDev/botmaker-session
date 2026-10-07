package com.botmaker.session.impl;

import com.botmaker.session.SessionOptions;
import com.botmaker.session.display.SessionDisplay;
import com.botmaker.session.launch.LaunchIsolation;
import com.botmaker.session.process.AppOutputLog;
import com.botmaker.session.process.SessionReaper;
import com.botmaker.session.process.SessionUnit;
import com.botmaker.session.remote.DisplayLink;
import com.botmaker.session.remote.WindowIds;
import com.botmaker.shared.Diag;
import com.botmaker.shared.capture.GenericWindow;
import com.botmaker.shared.emulator.EmulatorInstance;
import com.botmaker.shared.emulator.EmulatorInstances;
import com.botmaker.shared.emulator.EmulatorLauncher;
import com.botmaker.shared.launch.GameLauncher;
import com.botmaker.shared.launch.HostLauncherProbe;
import com.botmaker.shared.launch.LaunchKind;
import com.botmaker.shared.launch.LaunchPreparation;
import com.botmaker.shared.launch.LaunchSpec;
import com.botmaker.shared.launch.Launcher;

import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Supplier;

/**
 * Launching a target into a private session: stop the copy already running on the desktop, run each form of the
 * launch ladder in the session's reap group, and wait for the window it maps. Taken out of {@link NestedSession},
 * which hands it the session's parts and attaches what this finds.
 *
 * <p>It keeps what a launch leaves behind — the process it spawned and the file its output goes to — because the
 * session's health and teardown ask about them.
 */
final class PrivateLaunch {

    /** How long to wait for a launched game's window to appear on the nested display before giving up the attach. */
    static final long WINDOW_TIMEOUT_MS = 20_000;
    /**
     * The same budget for a <em>store launcher</em> kind, where the window we're waiting for is the game's and not
     * the process we spawned. Heroic/Steam boot their own runtime, then a Proton prefix, then (first run) download
     * winetricks/umu before the game ever maps — minutes, not seconds. Timing those out at the
     * {@link #WINDOW_TIMEOUT_MS} budget meant reaping a launcher that was still working, which is how a perfectly
     * healthy Heroic ended up producing a SIGTRAP coredump.
     */
    static final long LAUNCHER_WINDOW_TIMEOUT_MS = 120_000;
    private static final long POLL_MS = 150;

    /** What the session calls when a window has been found: attach it, and follow the launcher chain or not. */
    interface Attach {
        void accept(GenericWindow window, boolean viaLauncher);
    }

    private final String id;
    private final SessionReaper reaper;
    private final SessionDisplay display;
    private final DisplayLink link;
    private final SessionOptions options;
    private final Supplier<Map<String, String>> env;

    private volatile Process process;
    /** The launched app's captured stdout+stderr, or {@code null} until something has been launched. */
    private volatile AppOutputLog output;

    PrivateLaunch(String id, SessionReaper reaper, SessionDisplay display, DisplayLink link, SessionOptions options,
                  Supplier<Map<String, String>> env) {
        this.id = id;
        this.reaper = reaper;
        this.display = display;
        this.link = link;
        this.options = options;
        this.env = env;
    }

    /** The last process a launch spawned, or {@code null}. */
    Process process() {
        return process;
    }

    /** Stop following the app's output but keep the file: after a failed launch is when someone wants to read it. */
    void closeOutput() {
        AppOutputLog log = output;
        if (log != null) {
            log.close();
            Diag.log("[Session] " + id + ": app output kept at " + log.file().getAbsolutePath());
        }
    }

    /**
     * Launch {@code spec} and hand the window it produces to {@code attach}. Whether the target can be confined at
     * all is asked once, up front, by {@link LaunchIsolation}; otherwise each form of the ladder is tried until one
     * maps a window, within {@link #windowTimeoutFor the kind's window budget}. When none does, nothing is attached
     * — the caller must treat that as a loud failure, not fall back to {@code :0}.
     */
    void launch(LaunchSpec spec, Attach attach) {
        // One up-front question — "can this be confined at all?" — instead of three separate guards that each
        // answered part of it. A refusal here costs nothing; discovering the same thing after the launch costs
        // the whole window budget and reaps a half-booted launcher (the Electron SIGTRAP).
        LaunchIsolation.Verdict verdict = LaunchIsolation.check(spec);
        if (!verdict.isolatable()) {
            Diag.error("[Session] " + id + ": " + verdict.reason());
            return;
        }
        // Before the argv, because the one thing a launch changes about the host cannot be passed on a command
        // line: Waydroid's framebuffer size is a persistent Android property read only at container start. Done
        // ahead of stopHostInstance so the container it cycles is the one we're about to stop anyway.
        LaunchPreparation.prepare(spec, display.width(), display.height());
        // Only the forms that exist here, sized: an emulator app's rung is the compositor Android renders into.
        List<List<String>> candidates = LaunchIsolation.runnableLadder(spec, display.width(), display.height());
        stopHostInstance(spec);

        long windowTimeoutMs = windowTimeoutFor(spec, options);
        // One log for the whole ladder, opened before the first rung: when an early form fails and a later one
        // works, the reason the first didn't is the thing worth reading.
        AppOutputLog log = output == null ? (output = AppOutputLog.open(id)) : output;
        ProcessBuilder.Redirect sink = log == null ? ProcessBuilder.Redirect.DISCARD : log.redirect();
        for (List<String> command : candidates) {
            Set<Long> before = windowIdsOnDisplay();
            Process proc;
            try {
                // Both streams, same file: which message preceded which is itself evidence.
                proc = reaper.launch(SessionUnit.APP, command, env.get(), sink, sink);
            } catch (Exception e) {
                Diag.error("[Session] " + id + ": launching `" + String.join(" ", command) + "` failed: "
                    + e.getMessage() + " — trying the next launch form");
                continue;
            }
            process = proc;
            GenericWindow target = awaitWindow(proc, before, windowTimeoutMs);
            if (target != null) {
                // A store launcher's first window is its own UI, not the game's, and it stays alive behind the
                // game — so for those kinds the attach is provisional and the newest window keeps winning.
                boolean viaLauncher = HostLauncherProbe.routesThroughDaemon(spec.kind());
                attach.accept(target, viaLauncher);
                Diag.log("[Session] " + id + ": attached to '" + target.getTitle() + "' on " + display.displayName()
                    + (viaLauncher ? " — provisionally, it launches through " + spec.kind()
                    + " and the game's own window comes later" : ""));
                return;
            }
            Diag.error("[Session] " + id + ": `" + String.join(" ", command) + "` mapped no window on "
                + display.displayName() + " within " + windowTimeoutMs + "ms — trying the next launch form"
                + outputHint(log));
        }
        // Every form ran but nothing appeared on :N. Rather than guess, ask what the process table says actually
        // happened — and say where the app's own account of it is.
        Diag.error("[Session] " + id + ": " + spec.spec() + " launched but no window appeared on "
            + display.displayName() + ". " + LaunchIsolation.noWindowDiagnosis(spec) + outputHint(log));
    }

    /**
     * How long to wait for {@code spec}'s window: an explicit {@link SessionOptions#windowTimeoutMs()} when one is
     * set, else {@link #LAUNCHER_WINDOW_TIMEOUT_MS} for a kind routed through a store launcher (we're waiting on
     * the game it starts) and {@link #WINDOW_TIMEOUT_MS} otherwise — an {@code exe:}/{@code cli:} target <em>is</em>
     * the process we spawned, so a window that hasn't appeared in twenty seconds isn't coming.
     */
    static long windowTimeoutFor(LaunchSpec spec, SessionOptions options) {
        long explicit = options == null ? 0L : options.windowTimeoutMs();
        if (explicit > 0) {
            return explicit;
        }
        return spec != null && HostLauncherProbe.routesThroughDaemon(spec.kind())
            ? LAUNCHER_WINDOW_TIMEOUT_MS
            : WINDOW_TIMEOUT_MS;
    }

    /** {@code " Its output: <path>"}, or nothing when the log couldn't be opened. */
    private static String outputHint(AppOutputLog log) {
        return log == null ? "" : " Its output: " + log.file().getAbsolutePath();
    }

    /**
     * Force-stop any incarnation of {@code spec} already running on the host, so ours is the only one. This stops
     * the <em>game</em> by name; it deliberately does not kill the user's launcher <em>daemon</em>, which would
     * disrupt their whole session. That a running daemon would swallow our launch entirely is handled one step
     * earlier, by {@link HostLauncherProbe} refusing the launch outright.
     */
    private void stopHostInstance(LaunchSpec spec) {
        if (spec.kind() == LaunchKind.EMULATOR_APP) {
            stopHostEmulator(spec);
            return;
        }
        if (!Launcher.isRunning(spec)) {
            return;
        }
        String name = spec.fileName();
        if (name != null && !name.isBlank()) {
            Diag.log("[Session] " + id + ": stopping host instance of " + spec.spec() + " (" + name + ")");
            GameLauncher.kill(name);
        } else {
            Diag.error("[Session] " + id + ": " + spec.spec() + " is running on the host but can't be stopped by name");
        }
    }

    /**
     * Stop the whole emulator <em>session</em> before launching our own, not just the app: there is one Android
     * container per machine, and {@code waydroid app launch} talks to whichever session is already up. Leave the
     * host's running and the app appears on the user's desktop while this display waits out its whole budget.
     * Best-effort and quiet when there is nothing to stop.
     */
    private void stopHostEmulator(LaunchSpec spec) {
        EmulatorInstance instance = EmulatorInstances.byName(spec.emulatorInstance()).orElse(null);
        if (instance == null || !instance.canStop()) {
            return;
        }
        Diag.log("[Session] " + id + ": stopping the host " + instance.brand() + " session so ours owns the container");
        EmulatorLauncher.stop(instance);
    }

    /** All window ids currently on the nested display — the "before" snapshot the new-window attach diffs against. */
    private Set<Long> windowIdsOnDisplay() {
        Set<Long> ids = new HashSet<>();
        for (GenericWindow w : link.getAllWindows()) {
            ids.add(WindowIds.of(w));
        }
        return ids;
    }

    /**
     * Wait for the game's window and return it. Preference order: a window whose {@code _NET_WM_PID} is in the
     * launched process subtree (the robust match — Wine/Proton set it); else a window that appeared since
     * {@code before} (covers apps/WMs that don't set {@code _NET_WM_PID}); else {@code null} on timeout.
     */
    private GenericWindow awaitWindow(Process proc, Set<Long> before, long timeoutMs) {
        long deadline = System.currentTimeMillis() + timeoutMs;
        while (System.currentTimeMillis() < deadline) {
            Set<Long> pids = subtreePids(proc);
            GenericWindow newest = null;
            for (GenericWindow w : link.getAllWindows()) {
                long pid = link.windowPid(WindowIds.of(w));
                if (pid > 0 && pids.contains(pid)) {
                    return w; // strongest evidence — this window's own client is our process
                }
                if (!before.contains(WindowIds.of(w))) {
                    newest = w; // last new window wins (the most recently mapped top-level)
                }
            }
            if (newest != null) {
                return newest;
            }
            if (!proc.isAlive() && subtreePids(proc).isEmpty()) {
                Diag.error("[Session] " + id + ": launched process exited before a window appeared");
                return null;
            }
            sleep();
        }
        return null;
    }

    /** The pid of {@code proc} plus its live descendants — under systemd the payload descends from the scope. */
    private static Set<Long> subtreePids(Process proc) {
        Set<Long> pids = new HashSet<>();
        if (proc.isAlive()) {
            pids.add(proc.pid());
        }
        try {
            proc.descendants().forEach(h -> pids.add(h.pid()));
        } catch (Exception ignored) {
            // descendants() can race with exit; the pids we already have are enough.
        }
        return pids;
    }

    private static void sleep() {
        try {
            Thread.sleep(POLL_MS);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }
}
