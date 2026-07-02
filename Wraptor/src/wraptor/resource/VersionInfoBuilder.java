/*
 * To change this license header, choose License Headers in Project Properties.
 * To change this template file, choose Tools | Templates
 * and open the template in the editor.
 */
package wraptor.resource;

/**
 *
 * @author saliya
 */
import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;

/**
 * Hand-builds the binary blob that goes in an RT_VERSION resource - the data
 * Windows Explorer reads for the Details tab (File version, Product version,
 * Company name, File description, Copyright).
 *
 * There's no JDK or JNA helper for this; it's a manually-nested binary
 * structure (VS_VERSIONINFO containing a VS_FIXEDFILEINFO plus
 * StringFileInfo/VarFileInfo children, each individually length-prefixed and
 * 4-byte aligned). This mirrors exactly what windres/rc.exe emit for a
 * `VERSIONINFO` block - see MSDN "VS_VERSIONINFO" for the layout this follows.
 */
final class VersionInfoBuilder {

    private VersionInfoBuilder() {
    }

    /**
     * US English (0x0409) + Unicode codepage (1200 = 0x04B0) - the
     * near-universal default every toolchain uses.
     */
    private static final int LANG_US_ENGLISH = 0x0409;
    private static final int CODEPAGE_UNICODE = 1200;

    static byte[] build(String fileVersion, String productVersion, String companyName,
            String fileDescription, String copyright, String productName, String internalName,
            String originalFilename) {

        int[] fileVer = parseVersion(fileVersion);
        int[] prodVer = parseVersion(productVersion);

        byte[] fixedFileInfo = buildFixedFileInfo(fileVer, prodVer);

        ByteArrayOutputStream stringEntries = new ByteArrayOutputStream();
        writeIfPresent(stringEntries, "CompanyName", companyName);
        writeIfPresent(stringEntries, "FileDescription", fileDescription);
        writeIfPresent(stringEntries, "FileVersion", fileVersion);
        writeIfPresent(stringEntries, "InternalName", internalName);
        writeIfPresent(stringEntries, "LegalCopyright", copyright);
        writeIfPresent(stringEntries, "OriginalFilename", originalFilename);
        writeIfPresent(stringEntries, "ProductName", productName);
        writeIfPresent(stringEntries, "ProductVersion", productVersion);

        String tableKey = String.format("%04X%04X", LANG_US_ENGLISH, CODEPAGE_UNICODE);
        byte[] stringTable = buildBlock(tableKey, 0, (short) 1, null, stringEntries.toByteArray());
        byte[] stringFileInfo = buildBlock("StringFileInfo", 0, (short) 1, null, stringTable);

        byte[] translationValue = new byte[4];
        writeWordAt(translationValue, 0, LANG_US_ENGLISH);
        writeWordAt(translationValue, 2, CODEPAGE_UNICODE);
        byte[] translationVar = buildBlock("Translation", translationValue.length, (short) 0, translationValue, null);
        byte[] varFileInfo = buildBlock("VarFileInfo", 0, (short) 1, null, translationVar);

        ByteArrayOutputStream children = new ByteArrayOutputStream();
        children.write(stringFileInfo, 0, stringFileInfo.length);
        children.write(varFileInfo, 0, varFileInfo.length);

        return buildBlock("VS_VERSION_INFO", fixedFileInfo.length, (short) 0, fixedFileInfo, children.toByteArray());
    }

    private static void writeIfPresent(ByteArrayOutputStream out, String key, String value) {
        String v = value == null ? "" : value;
        byte[] entry = buildStringEntry(key, v);
        out.write(entry, 0, entry.length);
    }

    private static byte[] buildStringEntry(String key, String value) {
        byte[] valueBytes = toWideZ(value);
        int wValueLength = valueBytes.length / 2; // WCHAR count, including the null terminator
        return buildBlock(key, wValueLength, (short) 1, valueBytes, null);
    }

    private static byte[] buildFixedFileInfo(int[] fileVer, int[] prodVer) {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        writeDword(out, 0xFEEF04BDL);       // dwSignature
        writeDword(out, 0x00010000L);       // dwStrucVersion
        writeDword(out, pack(fileVer[0], fileVer[1])); // dwFileVersionMS
        writeDword(out, pack(fileVer[2], fileVer[3])); // dwFileVersionLS
        writeDword(out, pack(prodVer[0], prodVer[1])); // dwProductVersionMS
        writeDword(out, pack(prodVer[2], prodVer[3])); // dwProductVersionLS
        writeDword(out, 0x3FL);             // dwFileFlagsMask
        writeDword(out, 0L);                // dwFileFlags
        writeDword(out, 0x40004L);          // dwFileOS = VOS_NT_WINDOWS32
        writeDword(out, 0x1L);              // dwFileType = VFT_APP
        writeDword(out, 0L);                // dwFileSubtype
        writeDword(out, 0L);                // dwFileDateMS
        writeDword(out, 0L);                // dwFileDateLS
        return out.toByteArray(); // 13 DWORDs = 52 bytes
    }

    /**
     * Builds one WORD-length-prefixed VS_VERSIONINFO-style block: header
     * (wLength placeholder, wValueLength, wType, szKey), the value bytes (if
     * any), then the children bytes (if any) - each section 4-byte aligned,
     * matching what every VERSIONINFO consumer expects.
     */
    private static byte[] buildBlock(String key, int wValueLength, short wType, byte[] valueBytes, byte[] childrenBytes) {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        writeWord(out, 0); // wLength placeholder, patched below once the real size is known
        writeWord(out, wValueLength);
        writeWord(out, wType);
        byte[] keyBytes = toWideZ(key);
        out.write(keyBytes, 0, keyBytes.length);
        padTo4(out);
        if (valueBytes != null) {
            out.write(valueBytes, 0, valueBytes.length);
        }
        padTo4(out);
        if (childrenBytes != null) {
            out.write(childrenBytes, 0, childrenBytes.length);
        }
        padTo4(out);

        byte[] result = out.toByteArray();
        result[0] = (byte) (result.length & 0xFF);
        result[1] = (byte) ((result.length >> 8) & 0xFF);
        return result;
    }

    /**
     * Parses "1.2.3.4"-style strings into 4 clamped 0-65535 parts, defaulting
     * missing/invalid parts to 0.
     */
    private static int[] parseVersion(String s) {
        int[] parts = {0, 0, 0, 0};
        if (s == null) {
            return parts;
        }
        String[] split = s.trim().split("\\.");
        for (int i = 0; i < 4 && i < split.length; i++) {
            try {
                int v = Integer.parseInt(split[i].trim());
                parts[i] = Math.max(0, Math.min(65535, v));
            } catch (NumberFormatException ignored) {
                parts[i] = 0;
            }
        }
        return parts;
    }

    private static int pack(int hi, int lo) {
        return ((hi & 0xFFFF) << 16) | (lo & 0xFFFF);
    }

    private static byte[] toWideZ(String s) {
        byte[] chars = s.getBytes(StandardCharsets.UTF_16LE);
        byte[] result = new byte[chars.length + 2]; // + null terminator WCHAR
        System.arraycopy(chars, 0, result, 0, chars.length);
        return result;
    }

    private static void writeWord(ByteArrayOutputStream out, int value) {
        out.write(value & 0xFF);
        out.write((value >> 8) & 0xFF);
    }

    private static void writeWordAt(byte[] buf, int offset, int value) {
        buf[offset] = (byte) (value & 0xFF);
        buf[offset + 1] = (byte) ((value >> 8) & 0xFF);
    }

    private static void writeDword(ByteArrayOutputStream out, long value) {
        out.write((int) (value & 0xFF));
        out.write((int) ((value >> 8) & 0xFF));
        out.write((int) ((value >> 16) & 0xFF));
        out.write((int) ((value >> 24) & 0xFF));
    }

    private static void padTo4(ByteArrayOutputStream out) {
        while (out.size() % 4 != 0) {
            out.write(0);
        }
    }
}
