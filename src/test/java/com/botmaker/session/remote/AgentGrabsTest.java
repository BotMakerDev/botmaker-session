package com.botmaker.session.remote;

import com.botmaker.shared.capture.FrameGrab;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.awt.image.BufferedImage;
import java.io.IOException;
import java.lang.foreign.Arena;
import java.lang.foreign.MemorySegment;
import java.nio.channels.FileChannel;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.Arrays;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** The agent's half of a live grab: frames written to a file the caller maps, the file gone when it is no longer read. */
class AgentGrabsTest {

    @TempDir
    Path dir;

    @Test
    void aFrameIsReadBackFromTheFileItsAnswerNames() throws IOException {
        AtomicReference<BufferedImage> frame = new AtomicReference<>(frame(3, 2, 0xFF102030));
        try (AgentGrabs grabs = new AgentGrabs(dir)) {
            long id = grabs.open(FrameGrab.of(frame::get));
            String[] first = AgentProtocol.split(grabs.grab(id));
            assertEquals("1", first[0]);
            assertArrayEquals(pixels(3, 2, 0xFF102030), read(first));
            String[] second = AgentProtocol.split(grabs.grab(id));
            assertEquals("2", second[0]);
            assertEquals(first[3], second[3], "the same size keeps its file");

            frame.set(frame(4, 3, 0xFFABCDEF));
            String[] bigger = AgentProtocol.split(grabs.grab(id));
            assertNotEquals(first[3], bigger[3], "a bigger frame gets a file its size");
            assertFalse(Files.exists(Path.of(first[3])), "the smaller file is deleted");
            assertArrayEquals(pixels(4, 3, 0xFFABCDEF), read(bigger));

            frame.set(null);
            assertNull(grabs.grab(id), "no frame read, no answer");
            assertNull(grabs.grab(id + 1), "a grab never opened");
            grabs.close(id);
            assertFalse(Files.exists(Path.of(bigger[3])), "closing a grab deletes its file");
        }
    }

    @Test
    void closingTheAgentsGrabsDeletesEveryFile() throws IOException {
        AgentGrabs grabs = new AgentGrabs(dir);
        grabs.open(FrameGrab.of(() -> frame(2, 2, 0xFF000001)));
        grabs.open(FrameGrab.of(() -> frame(2, 2, 0xFF000002)));
        grabs.grab(1);
        grabs.grab(2);
        try (var files = Files.list(dir)) {
            assertEquals(2, files.count());
        }
        grabs.close();
        try (var files = Files.list(dir)) {
            assertEquals(0, files.count());
        }
    }

    /** The frame an answer of {@link AgentGrabs#grab} names, read as the caller reads it. */
    private static int[] read(String[] answer) throws IOException {
        int width = Integer.parseInt(answer[1]);
        int height = Integer.parseInt(answer[2]);
        try (Arena arena = Arena.ofConfined();
             FileChannel channel = FileChannel.open(Path.of(answer[3]), StandardOpenOption.READ)) {
            MemorySegment file = channel.map(FileChannel.MapMode.READ_ONLY, 0, channel.size(), arena);
            int[] into = new int[width * height];
            assertTrue(AgentProtocol.readFrame(file, Long.parseLong(answer[0]), width, height, into));
            return into;
        }
    }

    private static BufferedImage frame(int width, int height, int argb) {
        BufferedImage image = new BufferedImage(width, height, BufferedImage.TYPE_INT_ARGB);
        image.setRGB(0, 0, width, height, pixels(width, height, argb), 0, width);
        return image;
    }

    private static int[] pixels(int width, int height, int argb) {
        int[] pixels = new int[width * height];
        Arrays.fill(pixels, argb);
        return pixels;
    }
}
