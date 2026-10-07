package com.botmaker.session;

/**
 * A {@link DesktopSession} on a private display this process brought up — what {@link Sessions#startPrivate}
 * returns. It adds what only an owner can answer: which display and backend it is (to offer it to a bot, see
 * {@link Sessions#handoffArguments}), the display server's own window on the user's desktop, and whether it is
 * still alive.
 */
public interface PrivateSession extends DesktopSession {

    /** The backend hosting this session — a consumer offering it to another process has to pass it on. */
    SessionBackend backend();

    /**
     * The X id of the {@link #attached() attached} window, or {@code 0} when nothing is attached — so a consumer
     * never has to unwrap a JNA {@code Pointer} out of a window handle.
     */
    long attachedWindowId();

    /**
     * The X id of the display server's <em>own</em> window on the host desktop, or {@code 0} while it isn't known.
     *
     * <p>The one thing a host-side tool needs to point at a session. Studio's overlay editor captures this window
     * to draw over a running gamescope session: gamescope passes {@code -W/-H} and {@code -w/-h} as the same size,
     * so this window's pixels are 1:1 with what the bot sees and its coordinates need no mapping. Matching it by
     * <em>title</em> does not work — gamescope renames its output window after whatever app is running in it.
     *
     * <p>{@code 0} is a normal answer for the first seconds of a session, not an error: the window is found in the
     * background, and gamescope does not map it until a client maps something on its Xwayland.
     */
    long hostWindowId();

    /**
     * Stop keeping the host window out of sight — and, if the search for it hasn't finished yet, don't start.
     * Idempotent: a host-side tool that wants to look at the session calls it before looking.
     */
    void revealHostWindow();

    /**
     * Close this session if its display is gone, and say whether it did. A dead display is not a state a session
     * can come back from ({@link SessionHealth#DEAD}); whoever holds the session polls this and drops it.
     *
     * @return {@code true} if this call closed it (so a caller notifies once, not on every poll)
     */
    boolean closeIfDead();
}
