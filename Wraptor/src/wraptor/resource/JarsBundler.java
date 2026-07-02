package wraptor.resource;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.List;
import wraptor.model.ProjectConfig;

/**
 * Builds the binary blob embedded as the "JARS" RCDATA resource. Format
 * (little-endian, matches stub.c's extract_jars):
 *
 * uint32 fileCount repeat fileCount times: uint32 nameLen utf8 name uint64
 * dataLen bytes data
 *
 * The main jar is always written first (not that it matters to the stub, but
 * keeps the blob deterministic / easy to eyeball in a hex editor while
 * debugging).
 */
public class JarsBundler {

    public static byte[] build(ProjectConfig config) throws IOException {
        List<ProjectConfig.JarEntry> ordered = new ArrayList<>();
        if (config.mainJar() != null) {
            ordered.add(config.mainJar());
        }
        ordered.addAll(config.libJars());

        ByteArrayOutputStream out = new ByteArrayOutputStream();
        writeUInt32LE(out, ordered.size());

        for (ProjectConfig.JarEntry entry : ordered) {
            byte[] nameBytes = entry.file.getName().getBytes(StandardCharsets.UTF_8);
            byte[] data = Files.readAllBytes(entry.file.toPath());

            writeUInt32LE(out, nameBytes.length);
            out.write(nameBytes);
            writeUInt64LE(out, data.length);
            out.write(data);
        }
        return out.toByteArray();
    }

    private static void writeUInt32LE(OutputStream out, long value) throws IOException {
        ByteBuffer bb = ByteBuffer.allocate(4).order(ByteOrder.LITTLE_ENDIAN);
        bb.putInt((int) value);
        out.write(bb.array());
    }

    private static void writeUInt64LE(OutputStream out, long value) throws IOException {
        ByteBuffer bb = ByteBuffer.allocate(8).order(ByteOrder.LITTLE_ENDIAN);
        bb.putLong(value);
        out.write(bb.array());
    }
}
