package wraptor.resource;

import java.awt.image.BufferedImage;
import java.io.ByteArrayInputStream;
import java.io.File;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.file.Files;
import javax.imageio.ImageIO;

/**
 * Minimal, dependency-free .ico reader.
 *
 * Java's {@link ImageIO} has no built-in ICO reader at all. Modern .ico files
 * often store their larger frames as embedded PNGs, which ImageIO CAN decode
 * directly once extracted - that path is handled below. But the classic case,
 * and the one every icon editor still emits for the small sizes (16x16, 32x32),
 * is a raw Windows DIB: a BITMAPINFOHEADER followed by packed XOR (color)
 * pixel data and an AND (transparency) mask, with no PNG/JPEG wrapper at all.
 * That's what {@link #decodeDib} implements.
 */
public final class IcoDecoder {

    private IcoDecoder() {
    }

    public static BufferedImage decode(File icoFile) throws IOException {
        return decode(Files.readAllBytes(icoFile.toPath()));
    }

    public static BufferedImage decode(byte[] bytes) throws IOException {
        if (bytes.length < 6) {
            throw new IOException("Not an ICO file (too short)");
        }
        ByteBuffer buf = ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN);

        int reserved = buf.getShort(0) & 0xFFFF;
        int type = buf.getShort(2) & 0xFFFF;
        int count = buf.getShort(4) & 0xFFFF;
        if (reserved != 0 || type != 1 || count == 0) {
            throw new IOException("Not a valid .ico file (bad ICONDIR header)");
        }

        // Pick the largest frame (tie-break: highest color depth) - best for a UI preview.
        int bestOffset = -1, bestSize = -1, bestArea = -1, bestBpp = -1;
        for (int i = 0; i < count; i++) {
            int base = 6 + i * 16;
            if (base + 16 > bytes.length) {
                break;
            }
            int w = buf.get(base) & 0xFF;
            if (w == 0) {
                w = 256;
            }
            int h = buf.get(base + 1) & 0xFF;
            if (h == 0) {
                h = 256;
            }
            int bitCount = buf.getShort(base + 6) & 0xFFFF;
            int bytesInRes = buf.getInt(base + 8);
            int imageOffset = buf.getInt(base + 12);
            int area = w * h;
            if (area > bestArea || (area == bestArea && bitCount > bestBpp)) {
                bestArea = area;
                bestBpp = bitCount;
                bestOffset = imageOffset;
                bestSize = bytesInRes;
            }
        }
        if (bestOffset < 0 || bestSize <= 0 || bestOffset + bestSize > bytes.length) {
            throw new IOException("Could not locate a usable image frame inside the .ico file");
        }

        byte[] frame = new byte[bestSize];
        System.arraycopy(bytes, bestOffset, frame, 0, bestSize);

        if (isPng(frame)) {
            BufferedImage img = ImageIO.read(new ByteArrayInputStream(frame));
            if (img == null) {
                throw new IOException("Embedded PNG frame could not be decoded");
            }
            return img;
        }
        return decodeDib(frame);
    }

    private static boolean isPng(byte[] data) {
        return data.length > 8 && (data[0] & 0xFF) == 0x89
                && data[1] == 'P' && data[2] == 'N' && data[3] == 'G';
    }

    /**
     * Decodes a raw ICO "DIB" frame: a BITMAPINFOHEADER (ICO frames never have
     * the BITMAPFILEHEADER a standalone .bmp would) followed by the XOR color
     * bitmap and then the AND transparency mask, both stored bottom-up and
     * row-padded to 4 bytes, per the Windows ICO format.
     */
    private static BufferedImage decodeDib(byte[] data) throws IOException {
        if (data.length < 40) {
            throw new IOException("Truncated DIB header in .ico frame");
        }
        ByteBuffer buf = ByteBuffer.wrap(data).order(ByteOrder.LITTLE_ENDIAN);

        int headerSize = buf.getInt(0);
        int width = buf.getInt(4);
        int rawHeight = buf.getInt(8);
        int height = rawHeight / 2; // top half = XOR color data, bottom half = AND mask
        int bitCount = buf.getShort(14) & 0xFFFF;
        int compression = buf.getInt(16);

        if (width <= 0 || height <= 0) {
            throw new IOException("Invalid DIB dimensions in .ico frame");
        }
        if (compression != 0) { // 0 = BI_RGB, uncompressed
            throw new IOException("Compressed ICO frames (BI compression=" + compression + ") aren't supported");
        }

        int colorsUsed = headerSize >= 36 ? buf.getInt(32) : 0;
        int paletteEntries = (bitCount <= 8) ? (colorsUsed != 0 ? colorsUsed : (1 << bitCount)) : 0;

        int offset = headerSize; // BITMAPINFOHEADER is immediately followed by the palette, if any
        int[] palette = new int[paletteEntries];
        for (int i = 0; i < paletteEntries; i++) {
            int b = data[offset] & 0xFF;
            int g = data[offset + 1] & 0xFF;
            int r = data[offset + 2] & 0xFF;
            // 4th byte is reserved, unused in a DIB color table entry
            palette[i] = (r << 16) | (g << 8) | b;
            offset += 4;
        }

        int xorRowBytes = paddedRowBytes(width, bitCount);
        int xorStart = offset;
        int andRowBytes = paddedRowBytes(width, 1);
        int andStart = xorStart + xorRowBytes * height;

        int pixels = width * height;
        int[] rgb = new int[pixels];
        int[] alphaFromPixel = new int[pixels]; // only meaningful for 32bpp
        int[] alphaFromMask = new int[pixels];
        boolean anyPixelAlpha = false;

        for (int y = 0; y < height; y++) {
            int srcRow = height - 1 - y; // DIB rows are stored bottom-up
            int rowOffset = xorStart + srcRow * xorRowBytes;
            int andRowOffset = andStart + srcRow * andRowBytes;

            for (int x = 0; x < width; x++) {
                int idx = y * width + x;
                int rgbVal;
                switch (bitCount) {
                    case 32: {
                        int px = rowOffset + x * 4;
                        int b = data[px] & 0xFF, g = data[px + 1] & 0xFF, r = data[px + 2] & 0xFF, a = data[px + 3] & 0xFF;
                        rgbVal = (r << 16) | (g << 8) | b;
                        alphaFromPixel[idx] = a;
                        if (a != 0) {
                            anyPixelAlpha = true;
                        }
                        break;
                    }
                    case 24: {
                        int px = rowOffset + x * 3;
                        int b = data[px] & 0xFF, g = data[px + 1] & 0xFF, r = data[px + 2] & 0xFF;
                        rgbVal = (r << 16) | (g << 8) | b;
                        break;
                    }
                    case 8: {
                        int i = data[rowOffset + x] & 0xFF;
                        rgbVal = i < palette.length ? palette[i] : 0;
                        break;
                    }
                    case 4: {
                        int b0 = data[rowOffset + x / 2] & 0xFF;
                        int i = (x % 2 == 0) ? (b0 >> 4) : (b0 & 0x0F);
                        rgbVal = i < palette.length ? palette[i] : 0;
                        break;
                    }
                    case 1: {
                        int b0 = data[rowOffset + x / 8] & 0xFF;
                        int i = (b0 >> (7 - (x % 8))) & 1;
                        rgbVal = i < palette.length ? palette[i] : 0;
                        break;
                    }
                    default:
                        throw new IOException("Unsupported ICO bit depth: " + bitCount);
                }
                rgb[idx] = rgbVal;

                int andByte = data[andRowOffset + x / 8] & 0xFF;
                int maskBit = (andByte >> (7 - (x % 8))) & 1;
                alphaFromMask[idx] = (maskBit == 1) ? 0 : 255; // AND mask bit set = transparent
            }
        }

        // Some older 32bpp icons carry an all-zero alpha channel (buggy export tools) while
        // still providing a valid AND mask; trust the mask in that case instead of rendering
        // the whole icon invisible.
        boolean useMaskAlpha = (bitCount != 32) || !anyPixelAlpha;

        BufferedImage img = new BufferedImage(width, height, BufferedImage.TYPE_INT_ARGB);
        for (int y = 0; y < height; y++) {
            for (int x = 0; x < width; x++) {
                int idx = y * width + x;
                int a = useMaskAlpha ? alphaFromMask[idx] : alphaFromPixel[idx];
                img.setRGB(x, y, (a << 24) | (rgb[idx] & 0xFFFFFF));
            }
        }
        return img;
    }

    private static int paddedRowBytes(int width, int bitCount) {
        int bitsPerRow = width * bitCount;
        return ((bitsPerRow + 31) / 32) * 4;
    }
}
