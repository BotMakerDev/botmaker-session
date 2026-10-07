package com.botmaker.session.impl;

import com.botmaker.session.Capability;
import com.botmaker.session.PaintedSurface;
import com.botmaker.session.PreviewFrame;
import com.botmaker.session.PrivateSession;
import com.botmaker.session.SessionBackend;
import com.botmaker.session.SessionHealth;
import com.botmaker.session.SessionOptions;
import com.botmaker.session.SessionStartException;
import com.botmaker.session.display.GamescopeDisplay;
import com.botmaker.session.display.NestedDisplay;
import com.botmaker.session.display.SessionBackends;
import com.botmaker.session.display.SessionDisplay;
import com.botmaker.session.process.SessionBus;
import com.botmaker.session.process.SessionMembers;
import com.botmaker.session.process.SessionReaper;
import com.botmaker.session.process.SessionUnit;
import com.botmaker.session.remote.DisplayLink;
import com.botmaker.session.remote.WindowIds;
import com.botmaker.session.video.FfmpegVideoStream;
import com.botmaker.session.video.VideoPacket;
import com.botmaker.session.video.VideoStream;

import com.botmaker.shared.Diag;
import com.botmaker.shared.Executables;
import com.botmaker.shared.capture.GenericWindow;
import com.botmaker.shared.capture.NativeController;
import com.botmaker.shared.launch.LaunchSpec;
import com.botmaker.shared.platform.SessionEnv;

import java.awt.Rectangle;
import java.awt.image.BufferedImage;
import java.lang.ProcessBuilder.Redirect;
import java.util.EnumSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Consumer;

/**
 * A {@link PrivateSession} over a nested display the bot owns — the piece that makes background input
 * <em>flawless</em>. Because the game runs in its own Xephyr or gamescope {@code :N}, that display's global
 * pointer and keyboard focus are the bot's alone: the same device-level XTest injection that would hijack the
 * real cursor on {@code :0} is, on {@code :N}, both accepted by the game <em>and</em> invisible to the user
 * driving their real desktop. That is why — unlike {@link HostSession} — it honestly advertises
 * {@link Capability#BACKGROUND_CLICK}, {@link Capability#ISOLATED_FOCUS} and {@link Capability#MULTI_SESSION}.
 *
 * <p>Reached through {@code Sessions.startPrivate}. It <b>launches</b> its target (X11 has no window migration),
 * and everything it spawns — the X server, an optional window manager, the game — lives in one
 * {@link SessionReaper} group, so {@link #close()} reaps the whole tree. Two display backends sit behind one
 * {@link SessionDisplay} seam: {@link NestedDisplay} (Xephyr, 2D) and {@link GamescopeDisplay} (gamescope,
 * hardware 3D). The launch itself is {@link PrivateLaunch}'s, and keeping the server's black window off the user's
 * desktop is {@link HostWindowHider}'s; this class supervises the two and owns teardown.
 */
public final class NestedSession implements PrivateSession {

    /** Monotonic per-JVM counter so concurrent sessions get distinct reap-group ids (display numbers come from X). */
    private static final AtomicInteger SEQ = new AtomicInteger();

    /** How long to wait for an optional window manager to claim the display; a WM-less session proceeds anyway. */
    private static final long WM_TIMEOUT_MS = 5_000;
    private static final long POLL_MS = 150;
    /**
     * How long the payload gets to exit on {@code SIGTERM} at teardown before it is killed. Generous enough for a
     * launcher to close its windows, a Wine prefix to flush and each generation of a deep process tree to take its
     * own children down in turn; closing a session with nothing running still costs nothing, because the budget is
     * a deadline and not a wait.
     */
    private static final long MEMBER_SHUTDOWN_MS = 20_000;
    /**
     * How long an {@link #x11Capturable()} or {@link #videoSurface()} answer is reused. Both are X round trips asked
     * once per frame by a loop running at 24 fps, and both measure something that changes at the speed of a window
     * being mapped.
     */
    private static final long PROBE_TTL_MS = 1000;

    private final String id;
    private final SessionReaper reaper;
    private final SessionDisplay display;
    /**
     * Everything this session does to {@code :N}, held <b>in another process</b> — see {@link DisplayLink}. An X I/O
     * error used to call {@code exit(1)} on whichever process held the connection.
     */
    private final DisplayLink link;
    private final SessionOptions options;
    /** This session's own D-Bus bus (and Flatpak portal), or {@code null} when one couldn't be started. */
    private final SessionBus bus;
    private final SessionAttachment attachment;
    private final HostWindowHider hostWindow;
    private final PrivateLaunch launcher;
    private volatile boolean closed;

    private volatile boolean capturable = true;
    private volatile long capturableAt;
    private volatile PaintedSurface painted;
    private volatile long paintedAt;

    private NestedSession(String id, SessionReaper reaper, SessionDisplay display,
                          DisplayLink link, SessionOptions options, SessionBus bus) {
        this.id = id;
        this.reaper = reaper;
        this.display = display;
        this.link = link;
        this.options = options;
        this.bus = bus;
        this.attachment = new SessionAttachment(link, id + " on " + display.displayName());
        this.hostWindow = new HostWindowHider(id, display, options.backend(), link);
        this.launcher = new PrivateLaunch(id, reaper, display, link, options, this::sessionEnv);
        // The input backend asks for the driven window on every use rather than holding a handle, because
        // attached() re-resolves: the launcher chain routinely swaps the window out from under us.
        link.setDrivenWindow(this::attachedWindowId);
    }

    /**
     * Bring up a nested display (and its optional window manager), ready for a game to be {@link #launch}ed into
     * it. On any failure the partially-started tree is reaped before the exception propagates, so a caller can
     * cleanly fall back to the host session.
     */
    public static NestedSession start(SessionOptions options) throws SessionStartException {
        // Id shape s<pid>-<seq> is a contract: the orphan sweep parses the owner pid back out of the slice name.
        String id = "s" + ProcessHandle.current().pid() + "-" + SEQ.incrementAndGet();
        // Claimed here rather than after the tree is up, because the sweep below runs *concurrently* with the
        // bring-up: from its point of view an unclaimed slice owned by this pid is an abandoned one, and it would
        // stop the session being built right now. The claim is dropped again on a failed start.
        SessionReaper.claim(id);
        SessionReaper reaper = new SessionReaper(id);
        // Sweep the trees a previously-SIGKILLed JVM left behind — off the critical path. It touches only slices
        // this start doesn't own, so there is nothing for it to serialise against.
        sweepOrphansConcurrently();
        DisplayLink link = null;
        SessionBus bus = null;
        try {
            SessionDisplay display = startDisplay(reaper, options);
            // The bus comes up *after* the display and is given it, because the whole point is that the Flatpak
            // portal this bus activates inherits the private DISPLAY — see SessionBus.
            bus = SessionBackends.usesPrivateBus(options)
                ? SessionBus.start(reaper, id, Map.of(SessionEnv.DISPLAY, display.displayName()))
                : null;
            // The connection to :N is opened in a child process (DisplayLink) rather than here: when the display
            // server dies, Xlib's default I/O handler calls exit(1) in whichever process holds the connection. The
            // backend travels with it because it fixes the input policy.
            link = DisplayLink.open(display.displayName(), options.backend());
            if (link == null) {
                throw new SessionStartException("could not open " + display.displayName());
            }
            NestedSession session = new NestedSession(id, reaper, display, link, options, bus);
            session.startWindowManager();
            session.hostWindow.start();
            return session;
        } catch (SessionStartException e) {
            cleanupFailedStart(id, reaper, link, bus);
            throw e;
        } catch (Exception e) {
            cleanupFailedStart(id, reaper, link, bus);
            throw new SessionStartException("nested session start failed: " + e.getMessage(), e);
        }
    }

    /** Housekeeping on a daemon thread: nothing about this bring-up depends on it, so nobody waits for it. */
    private static void sweepOrphansConcurrently() {
        Thread sweep = new Thread(SessionReaper::reapOrphans, "session-orphan-sweep");
        sweep.setDaemon(true);
        sweep.start();
    }

    /** Bring up the display server the options ask for: Xephyr (2D) or gamescope (hardware 3D). */
    private static SessionDisplay startDisplay(SessionReaper reaper, SessionOptions options)
            throws SessionStartException {
        return switch (options.backend()) {
            case XEPHYR -> NestedDisplay.startXephyr(reaper, options.width(), options.height());
            case GAMESCOPE -> GamescopeDisplay.start(reaper, displayServerCommand(options),
                options.width(), options.height());
        };
    }

    /** The gamescope argv to launch: the options' override if set, else {@link GamescopeDisplay#defaultCommand}. */
    static List<String> displayServerCommand(SessionOptions options) {
        return options.gamescopeCommand().isEmpty()
            ? GamescopeDisplay.defaultCommand(options.width(), options.height())
            : options.gamescopeCommand();
    }

    /**
     * Reap a half-built session's resources in the reverse order they were acquired, then drop the claim
     * {@link #start} took — a session that never came up must not go on sheltering its own slice from the sweep.
     */
    private static void cleanupFailedStart(String id, SessionReaper reaper, DisplayLink link, SessionBus bus) {
        if (bus != null) {
            try { bus.close(); } catch (Throwable ignored) { }
        }
        if (link != null) {
            try { link.close(); } catch (Throwable ignored) { }
        }
        reaper.reap();
        SessionReaper.release(id);
    }

    /** Launch the resolved window manager (if any) into the nested display and wait, best-effort, for it. */
    private void startWindowManager() {
        List<String> wm = windowManagerCommandFor(options);
        if (wm.isEmpty()) {
            Diag.log("[Session] " + id + ": no window manager " + (options.backend() == SessionBackend.GAMESCOPE
                ? "(gamescope manages its own Xwayland)" : "— running WM-less"));
            return;
        }
        try {
            reaper.launch(SessionUnit.WM, wm, sessionEnv(), ProcessBuilder.Redirect.DISCARD);
        } catch (Exception e) {
            Diag.error("[Session] " + id + ": window manager launch failed: " + e.getMessage());
            return;
        }
        long deadline = System.currentTimeMillis() + WM_TIMEOUT_MS;
        while (System.currentTimeMillis() < deadline) {
            if (link.hasWindowManager()) {
                Diag.log("[Session] " + id + ": window manager is up");
                return;
            }
            sleep();
        }
        // A WM that never claims the display is a soft failure: input still reaches a mapped window without one.
        Diag.error("[Session] " + id + ": window manager did not claim " + display.displayName()
            + " within " + WM_TIMEOUT_MS + "ms — continuing WM-less");
    }

    @Override
    public Set<Capability> capabilities() {
        // The whole point of a bot-owned display: BACKGROUND_CLICK/ISOLATED_FOCUS/MULTI_SESSION, which a shared
        // :0 desktop cannot offer. HARDWARE_GL/VULKAN come only from the gamescope backend (Xephyr is 2D here).
        EnumSet<Capability> caps = EnumSet.of(
            Capability.ABSOLUTE_POINTER,
            Capability.RELATIVE_POINTER,
            Capability.BACKGROUND_CLICK,
            Capability.ISOLATED_FOCUS,
            Capability.MULTI_SESSION,
            Capability.SCREEN_CAPTURE,
            Capability.WINDOW_LAUNCH,
            Capability.WINDOW_ATTACH);
        if (display.hardwareAccelerated()) {
            caps.add(Capability.HARDWARE_GL);
            caps.add(Capability.VULKAN);
        }
        if (display.waylandDisplay() != null) {
            // Only a compositor backend answers this — gamescope with --expose-wayland. It is what lets a
            // Wayland-only client (Waydroid's show-full-ui) run in a session at all.
            caps.add(Capability.WAYLAND_CLIENTS);
        }
        return caps;
    }

    @Override
    public Rectangle screen() {
        return new Rectangle(0, 0, display.width(), display.height());
    }

    /**
     * {@code false} once this display has <b>no mapped X11 client at all</b> — which is what a Wayland-only payload
     * looks like from here, and the only thing about it that is observable. Counting mapped clients answers the
     * actual question: Waydroid maps none, Firestone maps one.
     *
     * <p>An <em>unaskable</em> display ({@code mappedCount() == -1}) counts as capturable rather than not: that is
     * a broken link, not an empty display, and consumers keep the session as their floor so the answer degrades to
     * a better source or to this same session, never past it to the user's real desktop.
     *
     * <p><b>Memoised</b> for {@value #PROBE_TTL_MS}&nbsp;ms.
     */
    @Override
    public boolean x11Capturable() {
        long now = System.currentTimeMillis();
        if (now - capturableAt > PROBE_TTL_MS) {
            capturable = link.mappedCount() != 0;
            capturableAt = now;
        }
        return capturable;
    }

    /**
     * The rect a stream of this session would encode, or {@code null} when nothing on it is painted — the public
     * face of {@link DisplayLink#paintedSurface}, memoised for {@value #PROBE_TTL_MS}&nbsp;ms. A caller watches it
     * to notice that the surface it is streaming has been replaced.
     */
    @Override
    public Rectangle videoSurface() {
        PaintedSurface surface = surface();
        return surface == null ? null : surface.rect();
    }

    /** The memoised choice itself — {@link #openVideoStream} needs the window id, not only the rect. */
    private PaintedSurface surface() {
        long now = System.currentTimeMillis();
        if (now - paintedAt > PROBE_TTL_MS) {
            painted = link.paintedSurface();
            paintedAt = now;
        }
        return painted;
    }

    @Override
    public void attach(GenericWindow window) {
        // An explicit attach names the window it wants, so stop following the launcher chain: the caller has
        // already answered the question the promotion exists to guess at.
        attachment.followLauncherChain(false);
        attachment.attach(window);
        if (window != null) {
            // There is something in the session now, so the host window is worth looking at.
            revealHostWindow();
        }
    }

    /**
     * The window this session drives — re-resolved when the one we attached to has gone ({@link SessionAttachment});
     * a closed session answers with the last resolved window rather than round-tripping to a dying display.
     */
    @Override
    public GenericWindow attached() {
        return closed ? attachment.current() : attachment.resolve();
    }

    /**
     * Launch {@code spec} into this display and attach to the window it produces ({@link PrivateLaunch}). When no
     * window appears, {@link #attached()} stays null — the caller must treat that as a loud failure, not fall back
     * to {@code :0}.
     */
    @Override
    public void launch(LaunchSpec spec) {
        if (closed || spec == null) {
            return;
        }
        try {
            launcher.launch(spec, (window, viaLauncher) -> {
                attach(window);
                attachment.followLauncherChain(viaLauncher);
            });
        } finally {
            // However that went, stop hiding the host window: a launch that produced nothing is much easier to
            // understand as an empty display than as a minimized window nobody thinks to look for.
            revealHostWindow();
        }
    }

    @Override
    public BufferedImage capture() {
        // Through attached(), not a field: a destroyed window otherwise captures null forever while the game is
        // running and capturable one window over.
        GenericWindow target = attached();
        return target == null ? null : link.captureWindow(target);
    }

    /**
     * The whole nested screen, which — unlike {@link #capture()} — does not depend on the attachment, so a viewer
     * streaming it keeps showing the session right through a launcher swapping its window for the game's.
     */
    @Override
    public BufferedImage captureScreen() {
        return link.captureScreen();
    }

    /** Straight through to the link, which is where the saving is — see {@link DisplayLink#previewFrame}. */
    @Override
    public PreviewFrame previewFrame(int maxEdge, float quality) {
        return link.previewFrame(maxEdge, quality);
    }

    /**
     * An {@code ffmpeg} grabbing {@code :N} directly, launched into this session's reap group so it dies with the
     * display it is reading. Declined, with {@code null}, where it could only produce black: no {@code ffmpeg}, a
     * Wayland-only client ({@link #x11Capturable()}), or nothing painted yet. It grabs the surface
     * {@link DisplayLink#paintedSurface} picks, by the rule the JPEG path applies to every frame.
     */
    @Override
    public VideoStream openVideoStream(int maxEdge, int fps, Consumer<VideoPacket> sink) {
        if (closed || !x11Capturable() || !Executables.onPath("ffmpeg")) {
            return null;
        }
        PaintedSurface target = surface();
        if (target == null || target.rect() == null || target.rect().isEmpty()) {
            return null;
        }
        return FfmpegVideoStream.open(display.displayName(), target, maxEdge, fps,
                sink, command -> reaper.launch(SessionUnit.VIDEO, command, sessionEnv(), Redirect.PIPE));
    }

    @Override
    public SessionHealth health() {
        if (closed || !display.alive()) {
            return SessionHealth.DEAD;
        }
        Process game = launcher.process();
        if (game != null && !game.isAlive()) {
            // Display and (any) WM are up but the game died — recoverable by relaunching into the same display.
            return SessionHealth.DEGRADED;
        }
        return SessionHealth.HEALTHY;
    }

    @Override
    public NativeController controller() {
        return link;
    }

    @Override
    public void close() {
        if (closed) {
            return;
        }
        closed = true;
        Diag.log("[Session] " + id + ": closing — host window off the desktop, payload, our X connections, then"
            + " the slice");
        // First, so the user never watches the window go black: the payload exits next, and the display server
        // only after it.
        hostWindow.withdraw();
        // Before anything the game depends on goes away. See shutdownMembers.
        shutdownMembers();
        launcher.closeOutput();
        try { link.close(); } catch (Throwable t) { Diag.error("[Session] " + id + ": display link close: " + t.getMessage()); }
        // The bus daemon itself belongs to the reaper (it is in the slice); this only drops its generated files.
        if (bus != null) {
            try { bus.close(); } catch (Throwable t) { Diag.error("[Session] " + id + ": bus close: " + t.getMessage()); }
        }
        reaper.reap();
        // Only now: until the slice is gone, a sweep running in this JVM (another session's start, Studio's boot)
        // must still count it as held, or it stops gamescope under the payload shutdownMembers is still waiting on.
        SessionReaper.release(id);
        // The display server's window has just gone with it; a compositor that was not tracking it would leave its
        // last frame on screen.
        hostWindow.repaintBehind();
        Diag.log("[Session] " + id + ": closed");
    }

    /**
     * Shut the payload down <em>before</em> the display server does — the step that makes teardown a shutdown
     * rather than a crash. {@link SessionReaper#reap()} alone is not enough for a Flatpak target, because
     * {@code flatpak run} moves the app out of our slice into its own scope (see {@link SessionMembers}): stopping
     * the slice killed gamescope and left the launcher to abort on the vanished X connection — the {@code SIGTRAP}
     * coredump that appeared on every live run.
     */
    private void shutdownMembers() {
        List<ProcessHandle> members = SessionMembers.of(display.displayName(),
            bus == null ? null : bus.address(), reaper.unitNamesExcept(SessionUnit.APP));
        if (members.isEmpty()) {
            return;
        }
        Diag.log("[Session] " + id + ": asking " + members.size() + " session process(es) to exit before "
            + display.displayName() + " goes away");
        long started = System.currentTimeMillis();
        List<ProcessHandle> survivors = SessionMembers.shutdown(members, MEMBER_SHUTDOWN_MS);
        if (survivors.isEmpty()) {
            Diag.log("[Session] " + id + ": session processes exited in " + (System.currentTimeMillis() - started) + "ms");
            // Not the same question as "the ones we signalled are gone": a launcher shutting down routinely spawns
            // one last helper, and the display server must not be reaped out from under it.
            awaitNoMembers(started + MEMBER_SHUTDOWN_MS);
            return;
        }
        // Not fatal — the slice reap follows — but these are exactly the processes it cannot reach.
        Diag.error("[Session] " + id + ": " + survivors.size() + " session process(es) survived SIGKILL: "
            + survivors.stream().map(SessionMembers::describe).reduce((a, b) -> a + ", " + b).orElse(""));
    }

    /**
     * Poll until nothing carries this session's environment any more, or {@code deadline} passes: every process
     * still connected to {@code :N} when the server is killed takes an X IO error.
     */
    private void awaitNoMembers(long deadline) {
        while (System.currentTimeMillis() < deadline) {
            List<ProcessHandle> stragglers = SessionMembers.of(display.displayName(),
                bus == null ? null : bus.address(), reaper.unitNamesExcept(SessionUnit.APP));
            if (stragglers.isEmpty()) {
                return;
            }
            Diag.log("[Session] " + id + ": still waiting on " + stragglers.size()
                + " late session process(es) before " + display.displayName() + " goes away: "
                + stragglers.stream().map(SessionMembers::describe).reduce((a, b) -> a + ", " + b).orElse(""));
            SessionMembers.shutdown(stragglers, Math.max(0, deadline - System.currentTimeMillis()));
            sleep();   // so an unkillable straggler costs a poll per pass, not a spin
        }
    }

    private static void sleep() {
        try {
            Thread.sleep(POLL_MS);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    /** The pid rooting this session's display-server tree — see {@link SessionDisplay#serverPid()}. */
    long serverPid() {
        return display.serverPid();
    }

    @Override
    public String displayName() {
        return display.displayName();
    }

    @Override
    public SessionBackend backend() {
        return options.backend();
    }

    @Override
    public long attachedWindowId() {
        return WindowIds.of(attached());
    }

    @Override
    public long hostWindowId() {
        return hostWindow.windowId();
    }

    @Override
    public void revealHostWindow() {
        hostWindow.reveal();
    }

    /** This session's reap-group id — for diagnostics and tests. */
    public String sessionId() {
        return id;
    }

    @Override
    public boolean closeIfDead() {
        if (closed || health() != SessionHealth.DEAD) {
            return false;
        }
        Diag.error("[Session] " + id + ": " + display.displayName() + " is gone — closing the session");
        close();
        return true;
    }

    // --- internals ---

    /** The child environment every process launched into this session gets: its private DISPLAY, plus extras. */
    private Map<String, String> sessionEnv() {
        Map<String, String> env = new LinkedHashMap<>();
        env.put(SessionEnv.DISPLAY, display.displayName());
        if (bus != null) {
            // The session's own bus — and with it its own Flatpak portal, so a launcher that re-spawns its game
            // through the portal lands back on :N instead of the host's :0. See SessionBus for the measurements.
            env.put(SessionEnv.DBUS_SESSION_BUS_ADDRESS, bus.address());
        }
        // A Wayland-capable client offered both will usually prefer Wayland — and the *host* compositor is exactly
        // what this session exists to stay out of. Blanking it forces the private X display, unless the display
        // hosts a compositor of its own (gamescope --expose-wayland), whose socket keeps the client inside the
        // session and is the only way a Wayland-only client can run here at all.
        String wayland = display.waylandDisplay();
        env.put(SessionEnv.WAYLAND_DISPLAY, wayland == null ? "" : wayland);
        env.putAll(options.extraEnv());
        return env;
    }

    /**
     * The window manager to actually run for {@code options}: what the caller asked for when it said anything at
     * all (including {@link SessionOptions#withoutWindowManager() "none"}), else the backend's policy from
     * {@link SessionBackends#windowManagerFor}. A window manager on a gamescope session is refused whoever asked
     * for it — gamescope already manages its Xwayland, and a second manager would fight it for the selection.
     */
    static List<String> windowManagerCommandFor(SessionOptions options) {
        if (options.backend() == SessionBackend.GAMESCOPE) {
            if (options.hasExplicitWindowManager() && !options.windowManagerCommand().isEmpty()) {
                Diag.error("[Session] ignoring window manager `" + String.join(" ", options.windowManagerCommand())
                    + "` — gamescope is the window manager for its own Xwayland");
            }
            return List.of();
        }
        return options.hasExplicitWindowManager()
            ? options.windowManagerCommand()
            : SessionBackends.windowManagerFor(options.backend());
    }
}
