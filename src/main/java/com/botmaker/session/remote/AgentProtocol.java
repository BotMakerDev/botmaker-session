package com.botmaker.session.remote;

import com.botmaker.shared.capture.GenericWindow;

import java.awt.Rectangle;
import java.awt.image.BufferedImage;
import java.awt.image.DataBufferInt;
import java.lang.foreign.MemorySegment;
import java.lang.foreign.ValueLayout;
import java.nio.ByteBuffer;
import java.util.ArrayList;
import java.util.List;

/**
 * The wire format between {@link RemoteDisplay} (in the caller's JVM) and {@link DisplayAgent} (in the child
 * that actually holds the {@code :N} connection). Deliberately a hand-rolled line protocol rather than JSON:
 * this module's whole point is a small dependency footprint (see {@code CLAUDE.md}), and the payload that
 * matters — a captured frame — is binary anyway, so a JSON body would have to carry it out-of-band regardless.
 *
 * <p><b>Request:</b> one line, tab-separated, {@code verb} first. <b>Response:</b> one line
 * {@code OK<TAB><payloadLength><TAB>field…} or {@code ERR<TAB><message>}, followed by exactly
 * {@code payloadLength} raw bytes. Every field is {@link #escape escaped}, so a window title containing a tab
 * or a newline — which X titles legitimately do — cannot desynchronise the stream.
 *
 * <p>The exchange is strictly serial: one request, one response, one payload. {@link RemoteDisplay} holds a
 * lock around each round trip and never evaluates a caller-supplied callback while holding it.
 */
final class AgentProtocol {

    /** Marks the response line of a call that succeeded; the fields after it are verb-specific. */
    static final String OK = "OK";
    /** Marks a call the agent could not serve. Never fatal on its own — the caller degrades, it doesn't throw. */
    static final String ERR = "ERR";

    private AgentProtocol() {
    }

    /** Escape the separators out of a field, so a title with a tab or a newline stays one field. */
    static String escape(String s) {
        if (s == null) {
            return "";
        }
        StringBuilder out = new StringBuilder(s.length());
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            switch (c) {
                case '\\' -> out.append("\\\\");
                case '\t' -> out.append("\\t");
                case '\n' -> out.append("\\n");
                case '\r' -> out.append("\\r");
                default -> out.append(c);
            }
        }
        return out.toString();
    }

    /** The inverse of {@link #escape}; a trailing lone backslash is dropped rather than throwing. */
    static String unescape(String s) {
        if (s == null || s.indexOf('\\') < 0) {
            return s == null ? "" : s;
        }
        StringBuilder out = new StringBuilder(s.length());
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            if (c != '\\' || i + 1 >= s.length()) {
                if (c != '\\') {
                    out.append(c);
                }
                continue;
            }
            char next = s.charAt(++i);
            switch (next) {
                case 't' -> out.append('\t');
                case 'n' -> out.append('\n');
                case 'r' -> out.append('\r');
                default -> out.append(next);
            }
        }
        return out.toString();
    }

    /** Join {@code parts} into one escaped, tab-separated line (no terminator). */
    static String join(Object... parts) {
        StringBuilder out = new StringBuilder();
        for (Object part : parts) {
            if (out.length() > 0) {
                out.append('\t');
            }
            out.append(escape(part == null ? "" : String.valueOf(part)));
        }
        return out.toString();
    }

    /** Split a line back into its unescaped fields; an empty line yields an empty array. */
    static String[] split(String line) {
        if (line == null || line.isEmpty()) {
            return new String[0];
        }
        String[] raw = line.split("\t", -1);
        String[] out = new String[raw.length];
        for (int i = 0; i < raw.length; i++) {
            out[i] = unescape(raw[i]);
        }
        return out;
    }

    /** Field {@code index} of {@code fields}, or {@code fallback} when it isn't there. */
    static String field(String[] fields, int index, String fallback) {
        return index >= 0 && index < fields.length ? fields[index] : fallback;
    }

    /** Field {@code index} as a long, or {@code fallback} for a missing or unparseable one. */
    static long asLong(String[] fields, int index, long fallback) {
        try {
            return Long.parseLong(field(fields, index, "").trim());
        } catch (NumberFormatException e) {
            return fallback;
        }
    }

    /** Field {@code index} as an int, or {@code fallback} for a missing or unparseable one. */
    static int asInt(String[] fields, int index, int fallback) {
        return (int) asLong(fields, index, fallback);
    }

    /** Field {@code index} as a boolean — only the literal {@code "true"} is true. */
    static boolean asBool(String[] fields, int index) {
        return "true".equals(field(fields, index, "false"));
    }

    // --- frames ---
    //
    // A capture asked for raw (its last argument "true") travels as its ARGB ints, big-endian, its width and
    // height the response's fields: lossless like the PNG it replaces, but a 1080p frame cost the agent ~250 ms
    // to deflate and the caller as much to inflate, the live board's whole frame budget eight times over. An
    // agent too old to know the argument ignores it and sends PNG, which the caller still reads.

    /** {@code image}'s pixels as ARGB ints, big-endian, row after row. */
    static byte[] rawPixels(BufferedImage image) {
        int w = image.getWidth();
        int h = image.getHeight();
        int[] pixels = image.getType() == BufferedImage.TYPE_INT_ARGB
                && image.getRaster().getDataBuffer() instanceof DataBufferInt ints
                && ints.getNumBanks() == 1 && ints.getOffset() == 0 && image.getRaster().getParent() == null
                && ints.getData().length == w * h
            ? ints.getData()
            : image.getRGB(0, 0, w, h, null, 0, w);
        ByteBuffer bytes = ByteBuffer.allocate(w * h * Integer.BYTES);
        bytes.asIntBuffer().put(pixels);
        return bytes.array();
    }

    /** The {@code TYPE_INT_ARGB} image {@link #rawPixels} wrote; null when {@code bytes} is not that many pixels. */
    static BufferedImage fromRawPixels(byte[] bytes, int width, int height) {
        if (width <= 0 || height <= 0 || bytes.length != (long) width * height * Integer.BYTES) {
            return null;
        }
        BufferedImage image = new BufferedImage(width, height, BufferedImage.TYPE_INT_ARGB);
        ByteBuffer.wrap(bytes).asIntBuffer().get(((DataBufferInt) image.getRaster().getDataBuffer()).getData());
        return image;
    }

    // --- frame files ---
    //
    // A live grab's frames cross through a file in /dev/shm that both processes map (2026-10-22, as OBS hands a
    // frame to its encoder): the agent writes the pixels there and answers only the frame's sequence, size and file,
    // so a 1080p frame costs one copy on each side instead of 8 MB down the pipe. The file holds the sequence (a
    // long), the width and the height (ints), then the pixels as ARGB ints row after row, all in the machine's own
    // byte order: both ends run on it. One slot is enough: a grab is read by one thread at a time, so the caller has
    // copied a frame out before it asks for the next.

    /** Bytes before a frame file's pixels: its sequence, width and height. */
    static final int FRAME_HEADER = Long.BYTES + 2 * Integer.BYTES;

    /** The bytes a frame file needs for a {@code width × height} frame. */
    static long frameFileSize(int width, int height) {
        return FRAME_HEADER + (long) width * height * Integer.BYTES;
    }

    /** {@code pixels}' first {@code width × height} written into {@code file}, its header last, as frame {@code sequence}. */
    static void writeFrame(MemorySegment file, long sequence, int[] pixels, int width, int height) {
        MemorySegment.copy(pixels, 0, file, ValueLayout.JAVA_INT_UNALIGNED, FRAME_HEADER, width * height);
        file.set(ValueLayout.JAVA_INT_UNALIGNED, Long.BYTES, width);
        file.set(ValueLayout.JAVA_INT_UNALIGNED, Long.BYTES + Integer.BYTES, height);
        file.set(ValueLayout.JAVA_LONG_UNALIGNED, 0, sequence);
    }

    /**
     * Frame {@code sequence} read out of {@code file} into {@code into}; false when the file holds another frame or
     * another size, or is too small for the one it says it holds.
     */
    static boolean readFrame(MemorySegment file, long sequence, int width, int height, int[] into) {
        if (width <= 0 || height <= 0 || file.byteSize() < frameFileSize(width, height)
                || into.length < width * height
                || file.get(ValueLayout.JAVA_LONG_UNALIGNED, 0) != sequence
                || file.get(ValueLayout.JAVA_INT_UNALIGNED, Long.BYTES) != width
                || file.get(ValueLayout.JAVA_INT_UNALIGNED, Long.BYTES + Integer.BYTES) != height) {
            return false;
        }
        MemorySegment.copy(file, ValueLayout.JAVA_INT_UNALIGNED, FRAME_HEADER, into, 0, width * height);
        return true;
    }

    // --- window lists ---
    //
    // A window travels as one line of `id title x y w h`, and a list of them as the response payload rather
    // than as response fields: the payload has a declared length, so a hundred windows cost one read instead
    // of a field count the reader has to trust.

    /** One window as a payload line: {@code id title x y w h}. */
    static String encodeWindow(GenericWindow window) {
        Rectangle r = window == null || window.getRect() == null ? new Rectangle() : window.getRect();
        return join(WindowIds.of(window), window == null ? "" : window.getTitle(), r.x, r.y, r.width, r.height,
                window == null ? GenericWindow.NO_PID : window.getPid(),
                window == null || window.getWindowClass() == null ? "" : window.getWindowClass());
    }

    /** Every window in {@code windows} as newline-separated payload text. */
    static String encodeWindows(List<GenericWindow> windows) {
        StringBuilder out = new StringBuilder();
        if (windows != null) {
            for (GenericWindow w : windows) {
                out.append(encodeWindow(w)).append('\n');
            }
        }
        return out.toString();
    }

    /**
     * Parse one payload line back into a window whose native handle is a plain {@code Long} — the caller-side
     * representation, which is exactly the point: no JNA {@code Pointer} to a display this JVM has never opened.
     * Returns {@code null} for a line that names no window.
     */
    static GenericWindow decodeWindow(String line) {
        String[] f = split(line);
        long id = asLong(f, 0, 0);
        if (id == 0) {
            return null;
        }
        return new GenericWindow(id, field(f, 1, ""),
            new Rectangle(asInt(f, 2, 0), asInt(f, 3, 0), asInt(f, 4, 0), asInt(f, 5, 0)),
            asLong(f, 6, GenericWindow.NO_PID), field(f, 7, ""));
    }

    /** Parse a whole window-list payload; unparseable lines are skipped rather than failing the call. */
    static List<GenericWindow> decodeWindows(String payload) {
        List<GenericWindow> out = new ArrayList<>();
        if (payload == null || payload.isBlank()) {
            return out;
        }
        for (String line : payload.split("\n")) {
            if (line.isBlank()) {
                continue;
            }
            GenericWindow w = decodeWindow(line);
            if (w != null) {
                out.add(w);
            }
        }
        return out;
    }
}
