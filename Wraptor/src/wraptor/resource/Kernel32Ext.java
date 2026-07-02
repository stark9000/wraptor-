package wraptor.resource;

import com.sun.jna.Native;
import com.sun.jna.Pointer;
import com.sun.jna.WString;
import com.sun.jna.win32.StdCallLibrary;
import com.sun.jna.win32.W32APIOptions;

/**
 * Minimal JNA binding for the three Win32 calls needed to patch resources into
 * an existing PE (.exe): BeginUpdateResource / UpdateResource /
 * EndUpdateResource. This is the same mechanism Resource Hacker / rcedit use,
 * and it's how ResourcePatcher injects CONFIG, JARS, the icon, and version info
 * into a copy of stub32.exe / stub64.exe without invoking a compiler.
 *
 * RT_RCDATA = 10, RT_ICON = 3, RT_GROUP_ICON = 14, RT_VERSION = 16, RT_MANIFEST
 * = 24 - passed as Pointer via MAKEINTRESOURCE-style casting.
 */
public interface Kernel32Ext extends StdCallLibrary {

    Kernel32Ext INSTANCE = Native.load("kernel32", Kernel32Ext.class, W32APIOptions.UNICODE_OPTIONS);

    // Unicode (W) variants - avoids ANSI-codepage marshalling pitfalls and is
    // what Resource Hacker / rcedit use under the hood.
    Pointer BeginUpdateResourceW(WString fileName, boolean deleteExistingResources);

    boolean UpdateResourceW(Pointer hUpdate, Pointer type, WString name, short language,
            Pointer data, int dataSize);

    // Overload for resource types identified by name (e.g. "CONFIG", "JARS")
    boolean UpdateResourceW(Pointer hUpdate, WString type, WString name, short language,
            Pointer data, int dataSize);

    // Overload for resources named by a numeric ordinal ID (MAKEINTRESOURCE-style)
    // rather than a string - used for the individual RT_ICON sub-images that a
    // RT_GROUP_ICON resource references by integer ID.
    boolean UpdateResourceW(Pointer hUpdate, Pointer type, Pointer name, short language,
            Pointer data, int dataSize);

    boolean EndUpdateResourceW(Pointer hUpdate, boolean discard);

    // ---- resource *discovery* on the untouched stub, before patching ----
    // Used to find out what identifier (numeric ID or string name) the
    // stub's own resource.rc actually gave its icon group, instead of
    // assuming a name like "APPICON" - guessing wrong silently adds a
    // second, unused icon group rather than replacing the real one.

    Pointer LoadLibraryExW(WString fileName, Pointer hFile, int flags);

    boolean FreeLibrary(Pointer hModule);

    boolean EnumResourceNamesW(Pointer hModule, Pointer type, EnumResNameCallback callback, Pointer lParam);

    boolean EnumResourceLanguagesW(Pointer hModule, Pointer type, WString name,
            EnumResLangCallback callback, Pointer lParam);

    /** Callback signature for EnumResourceNamesW; must use the stdcall convention on 32-bit JVMs. */
    interface EnumResNameCallback extends StdCallCallback {
        boolean callback(Pointer hModule, Pointer type, Pointer name, Pointer lParam);
    }

    /** Callback signature for EnumResourceLanguagesW. */
    interface EnumResLangCallback extends StdCallCallback {
        boolean callback(Pointer hModule, Pointer type, Pointer name, short language, Pointer lParam);
    }

    int LOAD_LIBRARY_AS_DATAFILE = 0x00000002;
    int LOAD_LIBRARY_AS_IMAGE_RESOURCE = 0x00000020;

    /** True if a name/type pointer from a resource API is actually an int ID (MAKEINTRESOURCE), not a real string pointer. */
    static boolean isIntResource(Pointer p) {
        return (Pointer.nativeValue(p) >>> 16) == 0;
    }

    static int intResourceValue(Pointer p) {
        return (int) (Pointer.nativeValue(p) & 0xFFFF);
    }

    /**
     * Builds an integer resource-type pointer the way MAKEINTRESOURCE does in
     * C.
     */
    static Pointer makeIntResource(int id) {
        return new Pointer(id);
    }
}
