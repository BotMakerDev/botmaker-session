package com.botmaker.session.impl;

import com.botmaker.session.SessionBackend;
import com.botmaker.session.display.SessionDisplay;
import com.botmaker.session.display.SessionHostWindow;
import com.botmaker.session.remote.DisplayLink;
import com.botmaker.shared.Diag;

/**
 * Keeps a private session's display-server window — the black rectangle Xephyr or gamescope maps on the user's
 * desktop — out of sight until the session has something to show, and takes it off the desktop first at
 * teardown. Taken out of {@link NestedSession}, which drives it: {@link #start} after bring-up, {@link #reveal}
 * on attach, {@link #withdraw} as close's first step.
 *
 * <p>The window is found on a background thread and the decisions arrive on others, so the window and what has
 * been decided about it ({@link SessionHostWindow.Visibility}, including before it was found) change together
 * under one lock.
 */
final class HostWindowHider {

    /**
     * Set to {@code false} to leave the display server's host window visible for the whole bring-up (the old
     * behaviour), and through teardown too ({@link #withdraw}). The escape hatch exists because minimizing it is a
     * host-WM-mediated operation on a window we don't own: if a compositor ever throttles an iconified server's
     * frames, capture would stall, and that is far worse than the black flash this hides.
     */
    static final String HIDE_UNTIL_READY_PROPERTY = "botmaker.session.hideuntilready";

    /**
     * How long to keep looking for the server's window on the host desktop. Generous because the search runs off
     * the start path and the thing it is hiding lasts up to {@link PrivateLaunch#LAUNCHER_WINDOW_TIMEOUT_MS}:
     * gamescope's output window showed up more than 3s after its Xwayland was connectable.
     */
    private static final long FIND_MS = 15_000;

    /** The last look for that window at teardown, when the search found none: brief, since close() waits on it. */
    private static final long TEARDOWN_FIND_MS = 300;

    private final String id;
    private final SessionDisplay display;
    private final SessionBackend backend;
    private final DisplayLink link;

    /** Guards {@link #window} and {@link #state} together: "publish the window" and "decide" are one step. */
    private final Object lock = new Object();
    /** The server's window on the host desktop while it is being kept out of sight, or {@code null}. */
    private volatile SessionHostWindow window;
    /** What has been decided about the window, including before it was found. */
    private volatile SessionHostWindow.Visibility state = SessionHostWindow.Visibility.PENDING;

    HostWindowHider(String id, SessionDisplay display, SessionBackend backend, DisplayLink link) {
        this.id = id;
        this.display = display;
        this.backend = backend;
        this.link = link;
    }

    private static boolean enabled() {
        return Boolean.parseBoolean(System.getProperty(HIDE_UNTIL_READY_PROPERTY, "true"));
    }

    /**
     * Minimize the display server's window until the session has a window of its own to put in it, off the start
     * path: the window can take seconds to appear, and a cosmetic nicety must never delay the launch it hides.
     * Best-effort and silent when the window can't be identified ({@link SessionHostWindow#find}).
     */
    void start() {
        if (!enabled()) {
            return;
        }
        Thread hider = new Thread(this::findAndHide, "session-host-window-hider-" + id);
        hider.setDaemon(true);
        hider.start();
    }

    private void findAndHide() {
        SessionHostWindow found = SessionHostWindow.find(display.serverPid(), backend.binaryName(),
            display.displayName(), FIND_MS);
        if (found == null) {
            Diag.log("[Session] " + id + ": no host window could be proved ours for " + backend.binaryName()
                + " on " + display.displayName()
                + " — leaving bring-up visible rather than minimizing a window that might be the user's");
            return;
        }
        synchronized (lock) {
            window = found;
            // Teardown began while this was still searching, and found nothing itself: withdraw what this found.
            if (state == SessionHostWindow.Visibility.WITHDRAWN) {
                found.withdraw();
                return;
            }
            // A reveal that arrived while the search was still running is the whole reason this is locked: the
            // window has to come up shown, not be hidden by a decision taken before the reveal existed.
            if (state == SessionHostWindow.Visibility.REVEALED) {
                Diag.log("[Session] " + id + ": found the host window after the session already had content"
                    + " — showing it");
                found.reveal();
                return;
            }
            // Don't hide a window that has something real in it — an empty session is the only thing worth
            // hiding. It is what makes this safe on gamescope, whose host window isn't even mapped until a client
            // maps something on its Xwayland, so by the time we can find it there is already content.
            int mapped = link.mappedCount();
            if (mapped != 0) {
                Diag.log("[Session] " + id + ": leaving the host window visible — "
                    + (mapped < 0 ? "could not read " + display.displayName() : mapped + " client(s) already on "
                    + display.displayName()));
                return;
            }
            found.hide(mapped);
            state = found.state();
        }
    }

    /** Stop hiding the window — and, if the search hasn't finished yet, don't start. Idempotent. */
    void reveal() {
        synchronized (lock) {
            if (state == SessionHostWindow.Visibility.WITHDRAWN) {
                return;
            }
            state = SessionHostWindow.Visibility.REVEALED;
            SessionHostWindow current = window;
            if (current != null) {
                current.reveal();
            }
        }
    }

    /**
     * Take the window off the desktop for good, as teardown's first step (2026-10-07): with the payload stopped
     * first and the server reaped last, gamescope's window showed black in between — the blip on every close. A
     * search that gave up before the window mapped left nothing to withdraw, so it runs once more, briefly, while
     * the server still lives.
     */
    void withdraw() {
        if (!enabled()) {
            return;
        }
        synchronized (lock) {
            state = SessionHostWindow.Visibility.WITHDRAWN;
            SessionHostWindow current = window;
            if (current == null && display.alive()) {
                current = SessionHostWindow.find(display.serverPid(), backend.binaryName(),
                    display.displayName(), TEARDOWN_FIND_MS);
                window = current;
            }
            if (current != null) {
                current.withdraw();
            }
        }
    }

    /** The window's X id on the host desktop, or {@code 0} while it isn't known. */
    long windowId() {
        SessionHostWindow current = window;
        return current == null ? 0 : current.windowId();
    }

    /**
     * After the server has gone: ask the host to repaint where its window was, since a compositor that was not
     * tracking it leaves its last frame on screen as a gray rectangle.
     */
    void repaintBehind() {
        SessionHostWindow current = window;
        if (current != null) {
            current.repaintHostBehind();
        }
    }
}
