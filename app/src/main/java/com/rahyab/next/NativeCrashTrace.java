package com.rahyab.next;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;

/** Reads the crashed thread's frames from Android's tombstone.proto. */
final class NativeCrashTrace {
    private static final int MAX_BYTES = 2 * 1024 * 1024;
    private static final int MAX_FRAMES = 20;

    private NativeCrashTrace() { }

    static String summarize(InputStream input) throws IOException {
        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        byte[] chunk = new byte[8192];
        while (bytes.size() < MAX_BYTES) {
            int count = input.read(chunk, 0, Math.min(chunk.length, MAX_BYTES - bytes.size()));
            if (count <= 0) break;
            bytes.write(chunk, 0, count);
        }

        ByteBuffer root = ByteBuffer.wrap(bytes.toByteArray());
        long crashedTid = -1;
        while (root.hasRemaining()) {
            int tag = (int) varint(root);
            if (tag == 48) { // Tombstone.tid, field 6
                crashedTid = varint(root);
                break;
            }
            skip(root, tag);
        }
        if (crashedTid < 0) return "";

        root.rewind();
        while (root.hasRemaining()) {
            int tag = (int) varint(root);
            if (tag != 130) { // Tombstone.threads, field 16
                skip(root, tag);
                continue;
            }
            ByteBuffer entry = message(root);
            long tid = -1;
            ByteBuffer thread = null;
            while (entry.hasRemaining()) {
                int entryTag = (int) varint(entry);
                if (entryTag == 8) tid = varint(entry);
                else if (entryTag == 18) thread = message(entry);
                else skip(entry, entryTag);
            }
            if (tid == crashedTid && thread != null) return frames(thread);
        }
        return "";
    }

    private static String frames(ByteBuffer thread) {
        StringBuilder output = new StringBuilder();
        int count = 0;
        while (thread.hasRemaining() && count < MAX_FRAMES) {
            int tag = (int) varint(thread);
            if (tag != 34) { // Thread.current_backtrace, field 4
                skip(thread, tag);
                continue;
            }
            ByteBuffer frame = message(thread);
            long pc = 0L;
            String symbol = "";
            String library = "";
            while (frame.hasRemaining()) {
                int frameTag = (int) varint(frame);
                switch (frameTag) {
                    case 8:
                        pc = varint(frame);
                        break;
                    case 34:
                        symbol = text(message(frame));
                        break;
                    case 50:
                        String path = text(message(frame));
                        library = path.substring(path.lastIndexOf('/') + 1);
                        break;
                    default:
                        skip(frame, frameTag);
                }
            }
            if (count > 0) output.append('\n');
            output.append('#').append(count++).append(" pc=0x").append(Long.toHexString(pc))
                    .append(' ').append(safe(library)).append(' ').append(safe(symbol));
        }
        return output.toString().trim();
    }

    private static String text(ByteBuffer value) {
        if (value.remaining() > 1024) return "";
        byte[] bytes = new byte[value.remaining()];
        value.get(bytes);
        return new String(bytes, StandardCharsets.UTF_8);
    }

    private static String safe(String value) {
        return value.matches("[A-Za-z0-9_./:$<>+*(), ~-]{1,240}") ? value : "";
    }

    private static ByteBuffer message(ByteBuffer source) {
        int length = length(source);
        ByteBuffer value = source.slice();
        value.limit(length);
        source.position(source.position() + length);
        return value;
    }

    private static int length(ByteBuffer source) {
        long value = varint(source);
        if (value < 0 || value > source.remaining()) throw new IllegalArgumentException("length");
        return (int) value;
    }

    private static void skip(ByteBuffer source, int tag) {
        switch (tag & 7) {
            case 0:
                varint(source);
                break;
            case 1:
                source.getLong();
                break;
            case 2:
                int length = length(source);
                source.position(source.position() + length);
                break;
            case 5:
                source.getInt();
                break;
            default:
                throw new IllegalArgumentException("wire type");
        }
    }

    private static long varint(ByteBuffer source) {
        long result = 0L;
        for (int shift = 0; shift < 64; shift += 7) {
            int value = source.get() & 0xff;
            result |= (long) (value & 0x7f) << shift;
            if ((value & 0x80) == 0) return result;
        }
        throw new IllegalArgumentException("varint");
    }
}
