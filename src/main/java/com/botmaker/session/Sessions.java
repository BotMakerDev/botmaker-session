package com.botmaker.session;

import com.botmaker.session.impl.AdoptedSession;
import com.botmaker.session.impl.HostSession;
import com.botmaker.session.impl.NestedSession;
import com.botmaker.session.process.SessionReaper;
import com.botmaker.shared.capture.NativeController;

import java.util.List;
import java.util.Optional;

/**
 * Where a session comes from — the one door to the classes under {@code impl}: a consumer holds a
 * {@link DesktopSession} or {@link PrivateSession} shaped by {@link SessionBackend} and {@link SessionOptions}, and
 * never names an {@code impl} class. The {@code display} and {@code launch} packages keep their own public helpers
 * ({@code SessionBackends}, {@code BackendInstall}, {@code GamescopeHost}, {@code BackgroundLauncher},
 * {@code LaunchIsolation}).
 *
 * <ul>
 *   <li>{@link #startPrivate}: bring up a private display of our own;</li>
 *   <li>{@link #offered()}: adopt the one the process that started us handed over ({@link #handoffArguments});</li>
 *   <li>{@link #host()}: the user's real desktop;</li>
 *   <li>{@link #reapOrphans()}: sweep what a crashed process left behind.</li>
 * </ul>
 */
public final class Sessions {

    private Sessions() {}

    /**
     * Brings up a private display (and its window manager, when the backend wants one), ready for a target to be
     * {@link DesktopSession#launch launched} into it. A failure reaps whatever was started before it is thrown.
     */
    public static PrivateSession startPrivate(SessionOptions options) throws SessionStartException {
        return NestedSession.start(options);
    }

    /**
     * The live private display the process that started us offered to us ({@link #handoffArguments}), attached
     * to the window it named, or empty when none was offered or it no longer accepts a connection.
     */
    public static Optional<DesktopSession> offered() {
        return Optional.ofNullable(AdoptedSession.fromProperties());
    }

    /**
     * The JVM arguments that offer {@code session} to a process about to be spawned — the producing half of
     * {@link #offered()}. Empty for {@code null}, so a caller composes it unconditionally.
     */
    public static List<String> handoffArguments(PrivateSession session) {
        return AdoptedSession.handoffArguments(session);
    }

    /** The user's real desktop, driven through the default native controller. */
    public static DesktopSession host() {
        return HostSession.ofDefault();
    }

    /** The user's real desktop, driven through {@code controller} — for a test's fake. */
    public static DesktopSession host(NativeController controller) {
        return new HostSession(controller);
    }

    /**
     * Reaps the process trees of private sessions this JVM no longer holds, and of sessions whose owning JVM has
     * died — the answer to "a bot crashed and left a gamescope running". Call it at startup and before deciding
     * whether a launch can be isolated: a leftover is read as a running launcher by the launch probes.
     * {@link #startPrivate} runs it alongside each new session too. No-op where there is no user systemd.
     */
    public static void reapOrphans() {
        SessionReaper.reapOrphans();
    }
}
