package wraptor.resource;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.List;

/**
 * Turns a standalone .ico file into the two resource shapes Windows actually
 * wants inside a PE: one RT_ICON entry per frame (the raw image data, PNG or
 * DIB, unchanged) plus a single RT_GROUP_ICON directory that lists those
 * frames by their assigned numeric resource ID.
 *
 * This is the same split Resource Hacker / rcedit / windres perform - a
 * standalone .ico's ICONDIR + ICONDIRENTRY[] (which embeds each frame's
 * *file offset*) gets rewritten into a GRPICONDIR + GRPICONDIRENTRY[] (which
 * instead embeds each frame's *resource ID*, since there's no "file offset"
 * concept once the frames are split into separate PE resources).
 *
 * ICONDIRENTRY (source, 16 bytes):
 *   BYTE  width, height, colorCount, reserved
 *   WORD  planes, bitCount
 *   DWORD bytesInRes, imageOffset
 *
 * GRPICONDIRENTRY (target, 14 bytes):
 *   BYTE  width, height, colorCount, reserved
 *   WORD  planes, bitCount
 *   DWORD bytesInRes
 *   WORD  id            <- replaces imageOffset
 *
 * Both share the same 6-byte ICONDIR/GRPICONDIR header:
 *   WORD reserved(0), WORD type(1), WORD count
 */
final class IconResourceBuilder {

    static final int RT_ICON = 3;
    static final int RT_GROUP_ICON = 14;

    static final class Frame {
        final int id;
        final byte[] data;
        final int width, height, colorCount, planes, bitCount;

        Frame(int id, byte[] data, int width, int height, int colorCount, int planes, int bitCount) {
            this.id = id;
            this.data = data;
            this.width = width;
            this.height = height;
            this.colorCount = colorCount;
            this.planes = planes;
            this.bitCount = bitCount;
        }
    }

    final List<Frame> frames;
    final byte[] groupDirectory;

    private IconResourceBuilder(List<Frame> frames, byte[] groupDirectory) {
        this.frames = frames;
        this.groupDirectory = groupDirectory;
    }

    static IconResourceBuilder parse(java.io.File icoFile) throws IOException {
        byte[] bytes = Files.readAllBytes(icoFile.toPath());
        if (bytes.length < 6) {
            throw new IOException("Not a valid .ico file (file is too short)");
        }
        ByteBuffer buf = ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN);

        int reserved = buf.getShort(0) & 0xFFFF;
        int type = buf.getShort(2) & 0xFFFF;
        int count = buf.getShort(4) & 0xFFFF;
        if (reserved != 0 || type != 1 || count == 0) {
            throw new IOException("Not a valid .ico file (bad ICONDIR header) - "
                    + "make sure it's a real Windows icon, not a renamed PNG/BMP.");
        }

        List<Frame> frames = new ArrayList<>();
        ByteArrayOutputStream groupBuf = new ByteArrayOutputStream();
        writeUInt16LE(groupBuf, 0);      // reserved
        writeUInt16LE(groupBuf, 1);      // type = icon
        writeUInt16LE(groupBuf, count);

        for (int i = 0; i < count; i++) {
            int base = 6 + i * 16;
            if (base + 16 > bytes.length) {
                throw new IOException("Truncated .ico file (entry " + i + " directory out of range)");
            }
            int width = buf.get(base) & 0xFF;          // 0 means 256 in the ICO spec
            int height = buf.get(base + 1) & 0xFF;
            int colorCount = buf.get(base + 2) & 0xFF;
            int planes = buf.getShort(base + 4) & 0xFFFF;
            int bitCount = buf.getShort(base + 6) & 0xFFFF;
            int bytesInRes = buf.getInt(base + 8);
            int imageOffset = buf.getInt(base + 12);

            if (bytesInRes <= 0 || imageOffset < 0 || (long) imageOffset + bytesInRes > bytes.length) {
                throw new IOException("Truncated .ico file (frame " + i + " image data out of range)");
            }

            byte[] frameData = new byte[bytesInRes];
            System.arraycopy(bytes, imageOffset, frameData, 0, bytesInRes);

            int id = i + 1; // 1-based numeric resource IDs for the RT_ICON entries
            frames.add(new Frame(id, frameData, width, height, colorCount, planes, bitCount));

            groupBuf.write(width);
            groupBuf.write(height);
            groupBuf.write(colorCount);
            groupBuf.write(0); // reserved
            writeUInt16LE(groupBuf, planes);
            writeUInt16LE(groupBuf, bitCount);
            writeUInt32LE(groupBuf, bytesInRes);
            writeUInt16LE(groupBuf, id);
        }

        return new IconResourceBuilder(frames, groupBuf.toByteArray());
    }

    private static void writeUInt16LE(ByteArrayOutputStream out, int value) {
        out.write(value & 0xFF);
        out.write((value >> 8) & 0xFF);
    }

    private static void writeUInt32LE(ByteArrayOutputStream out, long value) {
        out.write((int) (value & 0xFF));
        out.write((int) ((value >> 8) & 0xFF));
        out.write((int) ((value >> 16) & 0xFF));
        out.write((int) ((value >> 24) & 0xFF));
    }
}
