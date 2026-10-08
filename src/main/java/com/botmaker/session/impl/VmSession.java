package com.botmaker.session.impl;

import com.botmaker.session.Capability;
import com.botmaker.session.DesktopSession;
import com.botmaker.session.SessionHealth;
import com.botmaker.session.SessionStartException;
import com.botmaker.session.VmOptions;
import com.botmaker.shared.Diag;
import com.botmaker.shared.capture.GenericWindow;
import com.botmaker.shared.capture.NativeController;
import com.botmaker.shared.launch.LaunchSpec;
import com.botmaker.shared.launch.RunState;
import com.botmaker.shared.vm.GuestGame;
import com.botmaker.shared.vm.GuestLaunch;
import com.botmaker.shared.vm.GuestLauncher;
import com.botmaker.shared.vm.QmpEvents;

import java.awt.Point;
import java.awt.Rectangle;
import java.awt.image.BufferedImage;
import java.io.IOException;
import java.time.Duration;
import java.util.EnumSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;

/**
 * A game VM as a {@link DesktopSession}: a screen in the guest, over VNC, is the session's window, and
 * {@link #controller()} lists the guest's own windows on it. In a Windows guest that screen is the guest's own,
 * with its window list started at each connection; in a Linux guest it is a display of this session's own
 * ({@link com.botmaker.shared.vm.LinuxDisplay}), so several bots share the VM, and closing the session ends it.
 * The bot stays on the host; its clicks and keys reach the guest over VNC, so the user's own cursor stays free.
 * {@link #launch} starts the game there ({@link GuestLaunch}); {@link #running} and {@link #stop} find the game's
 * processes there ({@link GuestGame}, or the display's own).
 *
 * <p><b>It keeps the VM going.</b> QEMU runs with {@code -no-reboot}, as a restart inside it hangs under the
 * Windows Hypervisor Platform (doc 44 §4b.2.1), so when Windows restarts the guest (an update) QEMU ends and the
 * screen drops. A watcher sees the screen drop and asks why ({@link VmMachine.Connection#ended}): after a restart
 * it starts the VM again, waits for the guest to sign in, and launches the last target again, as the restart
 * closed it. A VM shut down on purpose stays down: the session is {@link SessionHealth#DEAD} and
 * {@link #endedBecause()} says why. {@link #controller()} follows each new connection, so a bot
 * holding it keeps working. After {@value #MAX_RESTARTS} restarts within {@link #CALM} of each other it gives
 * up and reports {@link SessionHealth#DEAD}.
 *
 * <p>{@link #close()} disconnects and leaves the VM running, for the next run; a Linux display ends, with its
 * game.
 */
public final class VmSession implements DesktopSession {

    /** Restarts in a row, each within {@link #CALM} of the last, before the session gives up. */
    static final int MAX_RESTARTS = 5;
    /** A connection that has lasted this long resets the restart count. */
    static final Duration CALM = Duration.ofMinutes(10);
    /** How long a dropped screen waits to hear the VM end: QEMU says why, then exits. */
    private static final Duration ENDED_WAIT = Duration.ofSeconds(5);

    /**
     * How often the watcher looks at the screen; how long a boot gets after the guest tools answer before a
     * launch, as the tools start before the user's desktop does and the launch task needs that desktop; and how
     * long a stopped game's store launcher gets to see it end, as Steam ignores a launch of a game it still
     * thinks runs.
     */
    record Timing(Duration watch, Duration settle, Duration afterStop) {
        static final Timing DEFAULT = new Timing(Duration.ofSeconds(1), Duration.ofSeconds(20), Duration.ofSeconds(3));
    }

    private final VmMachine machine;
    private final VmOptions options;
    private final Timing timing;
    private final NativeController input = new Following();
    private final Thread watcher;

    private volatile VmMachine.Connection screen;
    private volatile boolean attached = true;
    private volatile LaunchSpec launched;
    private volatile SessionHealth health = SessionHealth.HEALTHY;
    private volatile boolean closed;
    /** Why the VM stopped for good, once it has. */
    private volatile String ended;
    /** Touched by the watcher thread only. */
    private int restarts;
    private long connectedAt = System.nanoTime();

    private VmSession(VmMachine machine, VmOptions options, Timing timing, VmMachine.Connection screen) {
        this.machine = machine;
        this.options = options;
        this.timing = timing;
        this.screen = screen;
        this.watcher = new Thread(this::watch, "vm-session-" + machine.name());
        watcher.setDaemon(true);
    }

    /** Opens the VM named in {@code options}: starts it if it isn't running and waits for its guest. */
    public static VmSession start(VmOptions options) throws SessionStartException {
        VmMachine machine;
        try {
            machine = HypervisorVm.load(options.name());
        } catch (IOException e) {
            throw new SessionStartException(e.getMessage(), e);
        }
        return start(machine, options, Timing.DEFAULT);
    }

    static VmSession start(VmMachine machine, VmOptions options, Timing timing) throws SessionStartException {
        VmSession session = new VmSession(machine, options, timing, connect(machine, options, timing));
        session.watcher.start();
        Diag.log("[Session] VM " + machine.name() + ": connected to its screen");
        return session;
    }

    /** A connection to {@code machine}'s screen once its guest has signed in; nothing is left open on failure. */
    private static VmMachine.Connection connect(VmMachine machine, VmOptions options, Timing timing)
            throws SessionStartException {
        VmMachine.Connection connection = null;
        try {
            connection = machine.start();
            long until = System.nanoTime() + options.readyTimeout().toNanos();
            while (!machine.guestReady()) {
                if (System.nanoTime() > until) {
                    throw new SessionStartException("The game VM " + machine.name() + "'s Windows didn't sign in within "
                            + options.readyTimeout().toMinutes() + " minutes.");
                }
                Thread.sleep(timing.watch().toMillis());
            }
            if (connection.booted()) Thread.sleep(timing.settle().toMillis());
            connection = machine.signedIn(connection);
            return connection;
        } catch (IOException e) {
            closeQuietly(connection);
            throw new SessionStartException("The game VM " + machine.name() + " didn't start: " + e.getMessage(), e);
        } catch (InterruptedException e) {
            closeQuietly(connection);
            Thread.currentThread().interrupt();
            throw new SessionStartException("Opening the game VM " + machine.name() + " was stopped.", e);
        } catch (SessionStartException | RuntimeException e) {
            closeQuietly(connection);
            throw e;
        }
    }

    private void watch() {
        while (!closed) {
            try {
                Thread.sleep(timing.watch().toMillis());
            } catch (InterruptedException e) {
                return; // closed
            }
            if (closed) return;
            if (screen.alive()) {
                if (restarts > 0 && System.nanoTime() - connectedAt > CALM.toNanos()) restarts = 0;
                continue;
            }
            Optional<QmpEvents.Reason> why;
            try {
                why = screen.ended(ENDED_WAIT);
            } catch (InterruptedException e) {
                return; // closed
            }
            if (closed) return; // closing the session ends its listener too, which is no shutdown
            if (why.isPresent() && !why.get().restarts()) {
                // Shut down on purpose (its Start menu, Studio's Shut down, Task Manager): starting it again
                // would undo what the user did.
                ended = "The game VM " + machine.name() + " was shut down (" + why.get().displayName() + ").";
                health = SessionHealth.DEAD;
                Diag.log("[Session] VM " + machine.name() + ": " + why.get().displayName() + "; not starting it again");
                return;
            }
            if (restarts >= MAX_RESTARTS) {
                health = SessionHealth.DEAD;
                Diag.error("[Session] VM " + machine.name() + ": its screen dropped " + MAX_RESTARTS
                        + " times in a row; giving up");
                return;
            }
            restarts++;
            health = SessionHealth.DEGRADED;
            Diag.log("[Session] VM " + machine.name() + ": its screen dropped ("
                    + why.map(QmpEvents.Reason::displayName).orElse("the VM still runs") + "); connecting again");
            reconnect();
        }
    }

    /** Replaces the dropped connection; a failure leaves it dropped, for the next look to try again. */
    private void reconnect() {
        screen.close();
        VmMachine.Connection next;
        try {
            next = connect(machine, options, timing);
        } catch (SessionStartException e) {
            Diag.error("[Session] VM " + machine.name() + ": " + e.getMessage());
            return;
        }
        synchronized (this) {
            // Under the lock close() takes: a close between a check and the swap would leak the new connection.
            if (closed) {
                next.close();
                return;
            }
            screen = next;
        }
        connectedAt = System.nanoTime();
        LaunchSpec again = launched;
        if (next.runsNothing() && again != null) {
            try {
                start(again, false);
            } catch (RuntimeException e) {
                // The VM is back without its game: not healthy.
                Diag.error("[Session] VM " + machine.name() + ": " + e.getMessage());
                return;
            }
        }
        health = SessionHealth.HEALTHY;
    }

    @Override
    public Set<Capability> capabilities() {
        // The guest's pointer and focus are the bot's alone, and each VM has its own; no relative pointer over VNC.
        return EnumSet.of(Capability.ABSOLUTE_POINTER, Capability.BACKGROUND_CLICK, Capability.ISOLATED_FOCUS,
                Capability.MULTI_SESSION, Capability.SCREEN_CAPTURE, Capability.WINDOW_LAUNCH,
                Capability.WINDOW_ATTACH);
    }

    @Override
    public Rectangle screen() {
        Rectangle r = screen.window().getRect();
        return r == null ? new Rectangle(0, 0, 0, 0) : r;
    }

    /** Not an X display: the VM's name, for a log line or a status. */
    @Override
    public String displayName() {
        return "VM " + machine.name();
    }

    /** The guest's screen is the one window there is: attaching to anything attaches to it. */
    @Override
    public void attach(GenericWindow window) {
        attached = window != null;
    }

    @Override
    public GenericWindow attached() {
        return attached ? screen.window() : null;
    }

    /**
     * Starts {@code spec} in the guest, where the game is the guest's own: a path names a file in the guest, and
     * Steam or Epic is the launcher installed there. On a Windows guest's desktop, or on a Linux guest's display
     * of this session's own. A game already running there (the last run's) is left as it is, as a second copy of a
     * program would run beside it; before the first launch on a screen that {@link VmMachine.Connection#runsNothing},
     * the guest isn't asked.
     *
     * @throws IllegalArgumentException for a kind the guest can't start
     * @throws IllegalStateException    when the guest couldn't be reached
     */
    @Override
    public void launch(LaunchSpec spec) {
        boolean fresh = screen.runsNothing() && launched == null;
        if (!fresh && running(spec) == RunState.RUNNING) {
            launched = spec;
            attached = true;
            Diag.log("[Session] VM " + machine.name() + ": " + spec.describe() + " already runs");
            return;
        }
        start(spec, true);
    }

    /** {@link #launch}; {@code checkLauncher} off for a relaunch, whose launcher passed when it first launched. */
    private void start(LaunchSpec spec, boolean checkLauncher) {
        // The machine refuses, per guest, a kind it can't start (IllegalArgumentException).
        try {
            if (checkLauncher) requireLauncher(GuestLauncher.of(spec));
            machine.launch(spec);
        } catch (IOException e) {
            throw new IllegalStateException("The game VM " + machine.name() + " couldn't start "
                    + spec.describe() + ": " + e.getMessage(), e);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("Starting " + spec.describe() + " in the game VM was stopped.", e);
        }
        launched = spec;
        attached = true;
        Diag.log("[Session] VM " + machine.name() + ": started " + spec.describe());
    }

    /**
     * Stops a launch the guest can't answer: without its store launcher, Windows only offers to find an app for
     * the link, and the launch would look like it worked. A check that can't be made doesn't stop it.
     */
    private void requireLauncher(GuestLauncher launcher) throws InterruptedException {
        if (launcher == GuestLauncher.UNKNOWN) return;
        boolean has;
        try {
            has = machine.has(launcher);
        } catch (IOException e) {
            Diag.log("[Session] VM " + machine.name() + ": couldn't check for " + launcher.displayName() + ": "
                    + e.getMessage());
            return;
        }
        if (!has) {
            throw new IllegalStateException(launcher.displayName() + " isn't installed in the game VM " + machine.name()
                    + ". In ⚙ Bot Settings ▸ A virtual machine ▸ Open VM screen, install it, sign in, and install "
                    + "the game there.");
        }
    }

    /**
     * Asks the guest ({@link GuestGame}); a VM shut down runs nothing, and a guest that can't be asked is
     * {@link RunState#UNKNOWN}.
     */
    @Override
    public RunState running(LaunchSpec spec) {
        if (health == SessionHealth.DEAD) return RunState.STOPPED;
        return game(spec, false).state();
    }

    /**
     * Ends the game's process trees in the guest, then gives its store launcher {@link Timing#afterStop()} to see
     * it end; a guest that can't be asked, or a process that outlived it, is logged and ends nothing.
     */
    @Override
    public boolean stop(LaunchSpec spec) {
        if (health == SessionHealth.DEAD) return false;
        GuestGame.Found found = game(spec, true);
        boolean stopped = found.state() == RunState.RUNNING;
        if (stopped) {
            Diag.log("[Session] VM " + machine.name() + ": stopped " + String.join(", ", found.processes()));
            try {
                Thread.sleep(timing.afterStop().toMillis());
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        }
        return stopped;
    }

    private GuestGame.Found game(LaunchSpec spec, boolean stop) {
        try {
            return machine.game(spec, stop);
        } catch (IOException e) {
            Diag.log("[Session] VM " + machine.name() + ": " + e.getMessage());
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
        return GuestGame.Found.UNKNOWN;
    }

    @Override
    public BufferedImage capture() {
        return attached ? screen.capture() : null;
    }

    @Override
    public BufferedImage captureScreen() {
        return screen.capture();
    }

    @Override
    public SessionHealth health() {
        return health;
    }

    @Override
    public Optional<String> endedBecause() {
        return Optional.ofNullable(ended);
    }

    /** The screen's input, following each new connection the watcher makes. */
    @Override
    public NativeController controller() {
        return input;
    }

    /** Disconnects; the VM goes on running. */
    @Override
    public void close() {
        synchronized (this) {
            closed = true;
            screen.close();
        }
        watcher.interrupt();
    }

    private static void closeQuietly(VmMachine.Connection connection) {
        if (connection != null) connection.close();
    }

    /** {@link #controller()}: every call goes to the connection that is current when it is made. */
    private final class Following implements NativeController {

        private NativeController now() {
            return screen.controller();
        }

        @Override
        public GenericWindow getForegroundWindow() {
            return now().getForegroundWindow();
        }

        @Override
        public List<GenericWindow> getChildWindows(GenericWindow parent) {
            return now().getChildWindows(parent);
        }

        @Override
        public List<GenericWindow> getAllWindows() {
            return now().getAllWindows();
        }

        @Override
        public BufferedImage captureWindow(GenericWindow window) {
            return now().captureWindow(window);
        }

        @Override
        public void postLeftClick(GenericWindow window, int relativeX, int relativeY) {
            now().postLeftClick(window, relativeX, relativeY);
        }

        @Override
        public boolean supportsBackgroundInput() {
            return now().supportsBackgroundInput();
        }

        @Override
        public void focusWindow(GenericWindow window) {
            now().focusWindow(window);
        }

        @Override
        public void moveWindow(GenericWindow window, int x, int y) {
            now().moveWindow(window, x, y);
        }

        @Override
        public void resizeWindow(GenericWindow window, int width, int height) {
            now().resizeWindow(window, width, height);
        }

        @Override
        public void keyDown(int nativeKeyCode) {
            now().keyDown(nativeKeyCode);
        }

        @Override
        public void keyUp(int nativeKeyCode) {
            now().keyUp(nativeKeyCode);
        }

        @Override
        public void typeText(String text) {
            now().typeText(text);
        }

        @Override
        public void mouseMove(int xAbs, int yAbs) {
            now().mouseMove(xAbs, yAbs);
        }

        @Override
        public void mouseButton(int button, boolean press) {
            now().mouseButton(button, press);
        }

        @Override
        public void scroll(int amount) {
            now().scroll(amount);
        }

        @Override
        public Point cursorPosition() {
            return now().cursorPosition();
        }

        @Override
        public int pressHoldMs() {
            return now().pressHoldMs();
        }
    }
}
