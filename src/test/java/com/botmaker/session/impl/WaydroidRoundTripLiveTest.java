package com.botmaker.session.impl;

import com.botmaker.session.PrivateSession;
import com.botmaker.session.SessionBackend;
import com.botmaker.session.Sessions;
import com.botmaker.session.display.SessionBackends;
import com.botmaker.shared.emulator.AdbDevice;
import com.botmaker.shared.emulator.WaydroidStatus;
import com.botmaker.shared.launch.LaunchSpec;
import org.junit.jupiter.api.Test;

import java.awt.Rectangle;
import java.awt.image.BufferedImage;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

/**
 * Waydroid on a private display, end to end: the frame a bot reads off {@code :N} is Android's framebuffer pixel
 * for pixel, and a click at a pixel of that frame is a tap at the same Android coordinate.
 *
 * <p>The display is the emulator shape {@link SessionBackends#optionsFor} gives an {@code emu-app:} target —
 * Xephyr with no window manager, gamescope's SDL window covering it, Android inside — so this measures the
 * path a bot takes, not a hand-built one. Three checks, each one a way the chain has been wrong before:
 * <ol>
 *   <li>Android's {@code wm size} is the session's size (Waydroid boots at its persisted size, which
 *       {@code LaunchPreparation} must have set);</li>
 *   <li>the session's capture is the session's size, and agrees with {@code adb screencap} pixel for pixel
 *       where Android's screencap works at all (it does not on every GPU — see the skip below);</li>
 *   <li>a click through the session's controller at the centre of a row {@code uiautomator} reports opens
 *       that row — the tap reached Android at the coordinate the frame showed. Rows are about 100 px tall
 *       near y=1000, so a scale off by more than ~5% opens a different row and fails.</li>
 * </ol>
 *
 * <p><b>Opt-in twice:</b> {@code -Dbotmaker.live=true} and {@code -Dbotmaker.live.waydroid=true}, because it
 * stops and restarts this machine's Waydroid session to set its size.
 */
class WaydroidRoundTripLiveTest {

    private static final int WIDTH = 720;
    private static final int HEIGHT = 1280;
    private static final LaunchSpec SETTINGS = LaunchSpec.parse("emu-app:com.android.settings@Waydroid");
    /** A Settings row's bounds in a {@code uiautomator} dump: {@code text="Display" … bounds="[x1,y1][x2,y2]"}. */
    private static final Pattern ROW = Pattern.compile(
        "text=\"Display\"[^>]*?bounds=\"\\[(\\d+),(\\d+)]\\[(\\d+),(\\d+)]\"");

    @Test
    void aClickOnTheSessionFrameIsATapOnTheSameAndroidPixel() throws Exception {
        assumeLive();
        PrivateSession session = Sessions.startPrivate(
            SessionBackends.optionsFor(SETTINGS, SessionBackend.XEPHYR, WIDTH, HEIGHT));
        try (session) {
            session.launch(SETTINGS);
            assertNotNull(session.attached(), "Waydroid's window should have appeared on " + session.displayName());
            try (AdbDevice adb = awaitBooted(120_000)) {
                assertEquals("Physical size: " + WIDTH + "x" + HEIGHT, physicalSize(adb),
                    "Android must boot at the session's size, or every tap is scaled");

                Rectangle row = awaitRow(adb, 60_000);
                BufferedImage frame = session.capture();
                assertNotNull(frame, "the session should capture Waydroid's window");
                assertEquals(WIDTH, frame.getWidth(), "the frame is the session's width");
                assertEquals(HEIGHT, frame.getHeight(), "the frame is the session's height");                BufferedImage android = adb.screencap();
                if (android == null) {
                    // Measured on a hybrid AMD/NVIDIA laptop: Android's screencap fails inside Waydroid
                    // ("/vendor/etc/hwdata/amdgpu.ids: No such file or directory") and writes nothing. The
                    // session's capture is then the only reader, so the comparison has nothing to compare with.
                    System.out.println("[WaydroidRoundTrip] adb screencap unavailable, pixel check skipped: "
                        + adb.shell("screencap -p /sdcard/botmaker-cap.png 2>&1").trim());
                } else {
                    double diff = meanDifference(frame, android);
                    assertTrue(diff < 8, "the session's frame should be Android's framebuffer, mean channel "
                        + "difference was " + diff);
                }

                // The window is at the display's origin, so a frame pixel is a root coordinate.
                session.controller().click((int) row.getCenterX(), (int) row.getCenterY(), 1);
                assertTrue(awaitText(adb, "Brightness", 15_000),
                    "a click at the Display row's centre " + row + " should have opened Display settings");
            }
        }
    }

    // --- helpers ---

    private static AdbDevice awaitBooted(long timeoutMs) throws InterruptedException {
        long deadline = System.currentTimeMillis() + timeoutMs;
        RuntimeException last = null;
        while (System.currentTimeMillis() < deadline) {
            try {
                AdbDevice adb = AdbDevice.connect(WaydroidStatus.read().ipAddress(), WaydroidStatus.ADB_PORT);
                if (adb.bootCompleted()) {
                    return adb;
                }
                adb.close();
            } catch (RuntimeException e) {
                last = e;
            }
            Thread.sleep(1_000);
        }
        throw new AssertionError("Waydroid never finished booting over adb", last);
    }

    private static String physicalSize(AdbDevice adb) {
        return adb.shell("wm size").lines().filter(l -> l.startsWith("Physical size")).findFirst()
            .orElse("").trim();
    }

    private static Rectangle awaitRow(AdbDevice adb, long timeoutMs) throws InterruptedException {
        long deadline = System.currentTimeMillis() + timeoutMs;
        while (System.currentTimeMillis() < deadline) {
            Matcher m = ROW.matcher(dump(adb));
            if (m.find()) {
                int x1 = Integer.parseInt(m.group(1));
                int y1 = Integer.parseInt(m.group(2));
                return new Rectangle(x1, y1, Integer.parseInt(m.group(3)) - x1, Integer.parseInt(m.group(4)) - y1);
            }
            Thread.sleep(1_000);
        }
        throw new AssertionError("Settings never showed its Display row");
    }

    private static boolean awaitText(AdbDevice adb, String text, long timeoutMs) throws InterruptedException {
        long deadline = System.currentTimeMillis() + timeoutMs;
        while (System.currentTimeMillis() < deadline) {
            if (dump(adb).contains("text=\"" + text)) {
                return true;
            }
            Thread.sleep(500);
        }
        return false;
    }

    private static String dump(AdbDevice adb) {
        return adb.shell("uiautomator dump /sdcard/botmaker-ui.xml >/dev/null && cat /sdcard/botmaker-ui.xml");
    }

    /** Mean absolute difference per channel over a grid of samples; the two images must be the same size. */
    private static double meanDifference(BufferedImage a, BufferedImage b) {
        assertEquals(a.getWidth(), b.getWidth(), "screencap width");
        assertEquals(a.getHeight(), b.getHeight(), "screencap height");
        long sum = 0;
        int samples = 0;
        for (int y = 8; y < a.getHeight(); y += 16) {
            for (int x = 8; x < a.getWidth(); x += 16) {
                int p = a.getRGB(x, y);
                int q = b.getRGB(x, y);
                for (int shift = 0; shift <= 16; shift += 8) {
                    sum += Math.abs(((p >> shift) & 0xff) - ((q >> shift) & 0xff));
                }
                samples += 3;
            }
        }
        return (double) sum / samples;
    }

    private static void assumeLive() {
        assumeTrue(Boolean.getBoolean("botmaker.live") && Boolean.getBoolean("botmaker.live.waydroid"),
            "opt-in: -Dbotmaker.live=true -Dbotmaker.live.waydroid=true (restarts this machine's Waydroid)");
        String display = System.getenv("DISPLAY");
        assumeTrue(display != null && !display.isBlank(), "needs a DISPLAY");
        for (String exe : new String[]{"waydroid", "gamescope", "Xephyr"}) {
            assumeTrue(new java.io.File("/usr/bin/" + exe).canExecute(), "needs " + exe);
        }
    }
}
