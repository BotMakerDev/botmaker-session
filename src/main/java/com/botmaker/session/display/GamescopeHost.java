package com.botmaker.session.display;

import com.botmaker.shared.capture.GenericWindow;

import java.util.List;

/**
 * Recognises <b>gamescope's own output window</b> in a list of host windows.
 *
 * <p>gamescope is a compositor, and the window it opens on the desktop is not an application — it is the
 * surface its clients are painted into. Whoever is inside it (a game on a private display, Waydroid's Android
 * UI) is already offered as a capture target under its own name, so listing the host window <em>as well</em>
 * offers the same pixels twice under a name that means nothing to the user. That is exactly what the capture
 * picker did: one "Waydroid" tile and one "gamescope" tile, the first black (ADB {@code screencap} under a GPU
 * compositor) and the second not, with nothing to say they were the same Android.
 *
 * <p><b>Matched by title, on purpose.</b> gamescope sets both {@code WM_CLASS} and {@code _NET_WM_NAME} to
 * exactly {@code "gamescope"} (measured on a live window: {@code WM_CLASS(STRING) = "gamescope", "gamescope"}),
 * and {@link GenericWindow} carries the title on every platform while {@code WM_CLASS} is X11-only. An exact,
 * case-insensitive match — never {@code contains} — so a window merely *about* gamescope (an editor holding
 * this file, a terminal running it) is still listed.
 *
 * <p>Capturing such a window directly is a trap worth recording here, since this class is where someone will
 * look: X11 keeps no backing store, so an {@code x11grab} of an occluded gamescope window returns whatever is
 * in front of it — measured, and it returned the Studio window. Only the XComposite path
 * ({@link NativeController#captureWindow}) reads the window's own pixels.
 */
public final class GamescopeHost {

    /** gamescope's window title and {@code WM_CLASS}, both of which it sets to this exact string. */
    private static final String TITLE = "gamescope";

    private GamescopeHost() {}

    /** Whether {@code window} is a gamescope output window rather than an application's. */
    public static boolean isHost(GenericWindow window) {
        return window != null && window.getTitle() != null && TITLE.equalsIgnoreCase(window.getTitle().trim());
    }

    /** The first gamescope output window in {@code windows}, or {@code null} when there is none. */
    public static GenericWindow firstIn(List<GenericWindow> windows) {
        if (windows == null) {
            return null;
        }
        return windows.stream().filter(GamescopeHost::isHost).findFirst().orElse(null);
    }
}
