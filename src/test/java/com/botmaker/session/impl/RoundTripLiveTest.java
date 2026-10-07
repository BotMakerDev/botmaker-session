package com.botmaker.session.impl;

import com.botmaker.session.DesktopSession;
import com.botmaker.session.SessionBackend;
import com.botmaker.session.SessionOptions;
import com.botmaker.session.Sessions;
import com.botmaker.shared.capture.GenericWindow;
import com.botmaker.shared.launch.LaunchSpec;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.awt.Point;
import java.awt.Rectangle;
import java.awt.image.BufferedImage;
import java.io.File;
import java.net.URISyntaxException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Objects;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

/**
 * The resolution round trip a bot depends on, per place a game can run: <b>find a marker in the captured frame,
 * click its centre, and the game receives the click at that pixel.</b> Any scale, offset or letterbox between
 * the capture and the input path shows up as a miss, with the two coordinates in the message.
 *
 * <p>The game is {@code roundtrip/RoundTripTarget.java}, run as a single-file program by this JVM's own
 * {@code java}: a black window with a white square, which writes where the first press landed and exits. The
 * marker is found by its pixels, never by the numbers it was drawn at, so the test measures the capture rather
 * than trusting it.
 *
 * <p>Three places: {@code xephyr} and {@code gamescope} (a private display — the session's own controller, no
 * effect on the desktop), and {@code host} (the real desktop, through {@link Sessions#host()} — it moves your
 * cursor and clicks, so it needs {@code -Dbotmaker.live.host=true} besides {@code -Dbotmaker.live=true}).
 * Wayland, a scaled screen and several monitors are the same test run on such a desktop.
 */
class RoundTripLiveTest {

    private static final int WIDTH = 1280;
    private static final int HEIGHT = 720;
    /** Off-centre and odd, so a halved or shifted coordinate cannot land on it by luck. */
    private static final int MARKER_X = 937;
    private static final int MARKER_Y = 411;
    private static final int MARKER_SIZE = 24;

    @ParameterizedTest
    @ValueSource(strings = {"xephyr", "gamescope", "host"})
    void aClickAtTheMarkersPixelLandsOnTheMarker(String place) throws Exception {
        assumeLive(place);
        Path out = Files.createTempFile("botmaker-roundtrip", ".txt");
        Files.delete(out);
        LaunchSpec target = LaunchSpec.parse("cli:" + String.join(" ", javaBinary(), "-Dsun.java2d.uiScale=1",
            targetSource(), Integer.toString(WIDTH), Integer.toString(HEIGHT), Integer.toString(MARKER_X),
            Integer.toString(MARKER_Y), Integer.toString(MARKER_SIZE), out.toString()));
        DesktopSession session = switch (place) {
            case "xephyr" -> Sessions.startPrivate(SessionOptions.xephyr(WIDTH, HEIGHT));
            case "gamescope" -> Sessions.startPrivate(SessionOptions.gamescope(WIDTH, HEIGHT));
            default -> Sessions.host();
        };
        try (session) {
            session.launch(target);
            if (session.attached() == null) {
                session.attach(awaitWindowByTitle(session, "BotMakerRoundTrip", 20_000));
            }
            assertNotNull(session.attached(), "the target's window should have appeared (" + place + ")");

            // Xephyr's window manager places a new window after it maps: a click aimed by a rectangle read before
            // that lands where the window used to be (seen once in five runs, the click 400 px off).
            Rectangle window = awaitStableRect(session, 5_000);
            Rectangle marker = awaitMarker(session, 20_000);
            Point inFrame = new Point(marker.x + marker.width / 2, marker.y + marker.height / 2);
            session.controller().click(window.x + inFrame.x, window.y + inFrame.y, 1);

            String landed = awaitFile(out, 10_000);
            assertNotNull(landed, place + ": the click at frame " + inFrame + " (window at " + window.getLocation()
                + ") never reached the target");
            assertEquals(inFrame.x + "," + inFrame.y, landed, place + ": the frame showed the marker's centre at "
                + inFrame + "; the target received the click at " + landed);
        } finally {
            Files.deleteIfExists(out);
        }
    }

    // --- helpers ---

    /**
     * The white square's bounds in the session's frame. Polled, because a window maps before it first paints.
     */
    private static Rectangle awaitMarker(DesktopSession session, long timeoutMs) throws InterruptedException {
        long deadline = System.currentTimeMillis() + timeoutMs;
        while (System.currentTimeMillis() < deadline) {
            BufferedImage frame = session.capture();
            Rectangle found = frame == null ? null : whiteBounds(frame);
            if (found != null && found.width >= MARKER_SIZE / 2) {
                return found;
            }
            Thread.sleep(200);
        }
        throw new AssertionError("the marker never showed in the captured frame");
    }

    private static Rectangle whiteBounds(BufferedImage frame) {
        int minX = Integer.MAX_VALUE;
        int minY = Integer.MAX_VALUE;
        int maxX = -1;
        int maxY = -1;
        for (int y = 0; y < frame.getHeight(); y++) {
            for (int x = 0; x < frame.getWidth(); x++) {
                int p = frame.getRGB(x, y);
                if ((p & 0xf0f0f0) == 0xf0f0f0) {
                    minX = Math.min(minX, x);
                    minY = Math.min(minY, y);
                    maxX = Math.max(maxX, x);
                    maxY = Math.max(maxY, y);
                }
            }
        }
        return maxX < 0 ? null : new Rectangle(minX, minY, maxX - minX + 1, maxY - minY + 1);
    }

    /** The attached window's rectangle now — a window manager may have moved it since it was enumerated. */
    private static Rectangle currentRect(DesktopSession session) {
        GenericWindow attached = session.attached();
        return session.controller().getAllWindows().stream()
            .filter(w -> Objects.equals(w.getNativeHandle(), attached.getNativeHandle()))
            .map(GenericWindow::getRect)
            .findFirst()
            .orElse(attached.getRect());
    }

    /** The attached window's rectangle once it has not moved for half a second, or the last read at the deadline. */
    private static Rectangle awaitStableRect(DesktopSession session, long timeoutMs) throws InterruptedException {
        long deadline = System.currentTimeMillis() + timeoutMs;
        Rectangle last = currentRect(session);
        long since = System.currentTimeMillis();
        while (System.currentTimeMillis() < deadline) {
            Thread.sleep(100);
            Rectangle now = currentRect(session);
            if (!now.equals(last)) {
                last = now;
                since = System.currentTimeMillis();
            } else if (System.currentTimeMillis() - since >= 500) {
                return now;
            }
        }
        return last;
    }

    private static GenericWindow awaitWindowByTitle(DesktopSession session, String title, long timeoutMs)
            throws InterruptedException {
        long deadline = System.currentTimeMillis() + timeoutMs;
        while (System.currentTimeMillis() < deadline) {
            for (GenericWindow w : session.controller().getAllWindows()) {
                if (title.equals(w.getTitle())) {
                    return w;
                }
            }
            Thread.sleep(200);
        }
        return null;
    }

    private static String awaitFile(Path out, long timeoutMs) throws Exception {
        long deadline = System.currentTimeMillis() + timeoutMs;
        while (System.currentTimeMillis() < deadline) {
            if (Files.exists(out) && Files.size(out) > 0) {
                return Files.readString(out).trim();
            }
            Thread.sleep(100);
        }
        return null;
    }

    private static String javaBinary() {
        return ProcessHandle.current().info().command().orElse("java");
    }

    private static String targetSource() throws URISyntaxException {
        return Path.of(Objects.requireNonNull(RoundTripLiveTest.class.getResource("/roundtrip/RoundTripTarget.java"))
            .toURI()).toString();
    }

    private static void assumeLive(String place) {
        assumeTrue(Boolean.getBoolean("botmaker.live"), "opt-in live test — run with -Dbotmaker.live=true");
        String display = System.getenv("DISPLAY");
        assumeTrue(display != null && !display.isBlank(), "needs a DISPLAY");
        switch (place) {
            case "xephyr" -> assumeTrue(onPath(SessionBackend.XEPHYR.binaryName()), "needs Xephyr");
            case "gamescope" -> assumeTrue(onPath(SessionBackend.GAMESCOPE.binaryName()), "needs gamescope");
            default -> assumeTrue(Boolean.getBoolean("botmaker.live.host"),
                "clicks on the real desktop — run with -Dbotmaker.live.host=true");
        }
    }

    private static boolean onPath(String exe) {
        String path = System.getenv("PATH");
        if (path == null) {
            return false;
        }
        for (String dir : path.split(File.pathSeparator)) {
            if (new File(dir, exe).canExecute()) {
                return true;
            }
        }
        return false;
    }
}
