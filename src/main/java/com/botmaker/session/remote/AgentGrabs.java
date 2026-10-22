package com.botmaker.session.remote;

import com.botmaker.shared.capture.FrameGrab;
import com.botmaker.shared.capture.FrameSink;

import java.io.IOException;
import java.lang.foreign.Arena;
import java.lang.foreign.MemorySegment;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.nio.file.attribute.PosixFilePermissions;
import java.util.HashMap;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/**
 * The live grabs a {@link DisplayAgent} holds open, each writing its frames into a file the caller maps
 * ({@link AgentProtocol} § frame files, 2026-10-22). The caller deletes a file once it has mapped it, so nothing is
 * left in {@code /dev/shm} while both processes hold the memory; this deletes it too, on a new size, on close and at
 * exit, for a caller that never got that far.
 *
 * <p>Synchronized because the serving thread opens and grabs while a shutdown hook may close.
 */
final class AgentGrabs implements AutoCloseable {

    private final Path dir;
    private final String prefix = "botmaker-session-" + ProcessHandle.current().pid() + "-";
    private final Map<Long, Open> open = new HashMap<>();
    /** Every frame file not yet deleted, for {@link #deleteFiles}. */
    private final Set<Path> files = ConcurrentHashMap.newKeySet();
    private long next;

    AgentGrabs(Path dir) {
        this.dir = dir;
    }

    /** Where frame files go: {@code /dev/shm}, memory and never a disk, else the temp dir. */
    static Path frameDir() {
        Path shm = Path.of("/dev/shm");
        return Files.isDirectory(shm) ? shm : Path.of(System.getProperty("java.io.tmpdir"));
    }

    /** {@code grab} kept open under a new id, the one {@link #grab} and {@link #close(long)} take. */
    synchronized long open(FrameGrab grab) {
        long id = ++next;
        open.put(id, new Open(id, grab));
        return id;
    }

    /**
     * Grab {@code id}'s next frame into its file: the response's fields, its sequence, width, height and file, or
     * null when no frame could be read.
     */
    synchronized String grab(long id) throws IOException {
        Open grab = open.get(id);
        return grab == null ? null : grab.next();
    }

    synchronized void close(long id) {
        Open grab = open.remove(id);
        if (grab != null) grab.close();
    }

    @Override
    public synchronized void close() {
        open.values().forEach(Open::close);
        open.clear();
    }

    /**
     * Every frame file deleted, without the lock: for the shutdown hook, which must not wait on a grab stuck in an X
     * call. The mappings go with the process.
     */
    void deleteFiles() {
        files.forEach(AgentGrabs::delete);
    }

    private static void delete(Path file) {
        try {
            Files.deleteIfExists(file);
        } catch (IOException ignored) {
            // /dev/shm is cleared at boot; nothing else to do.
        }
    }

    /**
     * {@code size} bytes written as zeros: a file in {@code /dev/shm} mapped bigger than it is grows only where it is
     * touched, and a full tmpfs then kills the process with SIGBUS on the first frame instead of failing here.
     */
    private static void reserve(FileChannel channel, long size) throws IOException {
        ByteBuffer zeros = ByteBuffer.allocate((int) Math.min(size, 1 << 20));
        for (long at = 0; at < size; at += zeros.capacity()) {
            zeros.clear().limit((int) Math.min(zeros.capacity(), size - at));
            while (zeros.hasRemaining()) channel.write(zeros, at + zeros.position());
        }
    }

    /** One grab, the array it reads into and the file it writes. */
    private final class Open implements FrameSink {

        private final long id;
        private final FrameGrab grab;
        private int[] pixels = new int[0];
        private int width;
        private int height;
        private Arena arena;
        private MemorySegment file;
        private Path path;
        private int generation;
        private long sequence;

        Open(long id, FrameGrab grab) {
            this.id = id;
            this.grab = grab;
        }

        @Override
        public int[] pixels(int width, int height) {
            if (pixels.length < width * height) pixels = new int[width * height];
            this.width = width;
            this.height = height;
            return pixels;
        }

        String next() throws IOException {
            if (!grab.grab(this)) return null;
            if (file == null || file.byteSize() < AgentProtocol.frameFileSize(width, height)) map();
            AgentProtocol.writeFrame(file, ++sequence, pixels, width, height);
            return AgentProtocol.join(sequence, width, height, path);
        }

        /** A new file the size of this frame, the last one dropped: the caller maps a file once, at its size. */
        private void map() throws IOException {
            unmap();
            Path at = dir.resolve(prefix + id + "-" + ++generation + ".frame");
            Arena mapped = Arena.ofShared();
            try (FileChannel channel = FileChannel.open(at, Set.of(StandardOpenOption.CREATE_NEW,
                    StandardOpenOption.READ, StandardOpenOption.WRITE),
                    PosixFilePermissions.asFileAttribute(PosixFilePermissions.fromString("rw-------")))) {
                long size = AgentProtocol.frameFileSize(width, height);
                reserve(channel, size);
                file = channel.map(FileChannel.MapMode.READ_WRITE, 0, size, mapped);
            } catch (IOException | RuntimeException e) {
                mapped.close();
                Files.deleteIfExists(at);
                throw e;
            }
            arena = mapped;
            path = at;
            files.add(at);
        }

        private void unmap() {
            if (arena != null) arena.close();
            arena = null;
            file = null;
            if (path != null) {
                delete(path);
                files.remove(path);
            }
            path = null;
        }

        void close() {
            unmap();
            grab.close();
        }
    }
}
