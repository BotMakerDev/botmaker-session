package com.botmaker.session.launch;

import com.botmaker.session.SessionBackend;
import com.botmaker.session.SessionOptions;
import com.botmaker.shared.launch.LaunchSpec;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Pure coverage of the shared background launcher's backend shaping and default sizing — the logic that maps
 * Xephyr (2D) vs gamescope (3D) onto {@code SessionOptions} and applies the fallback display size. The
 * live bring-up ({@code Sessions.startPrivate} → launch → started listeners) needs a real X server and is verified
 * manually / by the shared live suite.
 */
class BackgroundLauncherTest {

    private static LaunchSpec game() {
        return LaunchSpec.parse("exe:/usr/bin/game");
    }

    @Test
    void xephyrIsThe2DBackendAtTheRequestedSize() {
        SessionOptions o = BackgroundLauncher.optionsFor(game(), SessionBackend.XEPHYR, 1600, 900);
        assertEquals(SessionBackend.XEPHYR, o.backend());
        assertEquals(1600, o.width());
        assertEquals(900, o.height());
    }

    @Test
    void gamescopeIsTheOptInHardware3DBackend() {
        SessionOptions o = BackgroundLauncher.optionsFor(game(), SessionBackend.GAMESCOPE, 1920, 1080);
        assertEquals(SessionBackend.GAMESCOPE, o.backend());
        assertEquals(1920, o.width());
        assertEquals(1080, o.height());
    }

    @Test
    void nonPositiveSizeFallsBackToTheDefault() {
        SessionOptions o = BackgroundLauncher.optionsFor(game(), SessionBackend.XEPHYR, 0, -5);
        assertEquals(BackgroundLauncher.DEFAULT_WIDTH, o.width());
        assertEquals(BackgroundLauncher.DEFAULT_HEIGHT, o.height());
    }

    /**
     * An emulator app brings its own compositor (gamescope is its child, not its display), so the display it
     * runs on must not manage windows: a window manager would frame and resize that compositor's window, which
     * is precisely the scaling the private display exists to avoid.
     */
    @Test
    void anEmulatorAppRunsOnAnUnmanagedDisplay() {
        LaunchSpec waydroid = LaunchSpec.parse("emu-app:com.example.game@Waydroid");
        SessionOptions o =
                BackgroundLauncher.optionsFor(waydroid, SessionBackend.XEPHYR, 1080, 1920);
        assertTrue(o.hasExplicitWindowManager() && o.windowManagerCommand().isEmpty(),
                "must be an explicit none — a default openbox would resize gamescope's window");
        assertEquals(1080, o.width());
        assertEquals(1920, o.height());
    }
}
