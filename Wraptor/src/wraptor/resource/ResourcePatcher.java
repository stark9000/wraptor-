package wraptor.resource;

import com.sun.jna.Memory;
import com.sun.jna.Native;
import com.sun.jna.Pointer;
import com.sun.jna.WString;

import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import wraptor.model.ProjectConfig;

/**
 * Patches a copy of the prebuilt native stub with the project's CONFIG and JARS
 * resources (and, as a follow-up step, icon + version info) using the Win32
 * BeginUpdateResource/UpdateResource/EndUpdateResource API via JNA.
 *
 * This never invokes a compiler - stub32.exe/stub64.exe were compiled once,
 * ahead of time, by native-stub/Makefile. This class only edits the PE resource
 * table of a copy of that file.
 */
public class ResourcePatcher {

    private static final short LANG_NEUTRAL = 0;
    private static final int RT_RCDATA = 10;
    private static final int RT_VERSION = 16;
    private static final int RT_MANIFEST = 24;

    private final java.util.function.Consumer<String> log;

    public ResourcePatcher() {
        this(msg -> { });
    }

    public ResourcePatcher(java.util.function.Consumer<String> log) {
        this.log = log;
    }

    /**
     * @param stubTemplate one of the bundled stub32.exe / stub64.exe resources
     * @param outputExe destination path for the finished, patched exe
     * @param config fully populated project configuration
     */
    public void build(File stubTemplate, File outputExe, ProjectConfig config) throws IOException {
        Files.createDirectories(outputExe.getParentFile().toPath());
        Files.copy(stubTemplate.toPath(), outputExe.toPath(), StandardCopyOption.REPLACE_EXISTING);

        byte[] configBytes = config.toConfigBytes();
        byte[] jarsBytes = JarsBundler.build(config);

        // Everything below is discovered from the *pristine* stubTemplate,
        // not the in-progress outputExe copy - BeginUpdateResource opens
        // outputExe for exclusive resource editing, so reading it back
        // mid-update would be at best redundant and at worst racy.

        // windres compiles every resource in a .rc under one language ID
        // unless a LANGUAGE block says otherwise - for this stub that's
        // 1033 (en-US), not the neutral (0) UpdateResource defaults to.
        // Writing under the wrong language doesn't overwrite the existing
        // resource, it silently ADDS a second same-name entry under a
        // different language, and whichever one Windows' language-
        // negotiation picks at load time wins - which may not be ours.
        // Discovering and matching the real language avoids that class of
        // bug entirely, for every resource we write, not just the icon.
        short stubLanguage = discoverStubLanguage(stubTemplate);
        log.accept("Stub resource language: " + (stubLanguage & 0xFFFF)
                + (stubLanguage == LANG_NEUTRAL ? " (neutral)" : ""));

        List<Object> existingIconGroupIds = (config.iconFile != null)
                ? discoverExistingResourceIdentifiers(stubTemplate, IconResourceBuilder.RT_GROUP_ICON)
                : Collections.emptyList();
        List<Object> existingVersionIds = discoverExistingResourceIdentifiers(stubTemplate, RT_VERSION);
        List<Object> existingManifestIds = config.requestAdminPrivileges
                ? discoverExistingResourceIdentifiers(stubTemplate, RT_MANIFEST)
                : Collections.emptyList();

        Pointer hUpdate = beginUpdateResourceWithRetry(outputExe);

        boolean ok = true;
        log.accept("Writing CONFIG resource...");
        ok &= writeResource(hUpdate, "CONFIG", configBytes, stubLanguage);
        log.accept("Writing JARS resource (" + jarsBytes.length + " bytes)...");
        ok &= writeResource(hUpdate, "JARS", jarsBytes, stubLanguage);

        if (config.iconFile != null) {
            log.accept("Embedding icon: " + config.iconFile.getName() + " ...");
            ok &= writeIcon(hUpdate, config.iconFile, existingIconGroupIds, stubLanguage);
        }

        log.accept("Writing version info (EXE Info tab)...");
        ok &= writeVersionInfo(hUpdate, config, existingVersionIds, stubLanguage);

        if (config.requestAdminPrivileges) {
            log.accept("Swapping in admin-required manifest...");
            ok &= writeAdminManifest(hUpdate, existingManifestIds, stubLanguage);
        }

        if (!ok) {
            int err = Native.getLastError();
            Kernel32Ext.INSTANCE.EndUpdateResourceW(hUpdate, true); // discard=true, don't commit a partial write
            throw new IOException("UpdateResource failed while writing resources into " + outputExe
                    + " (Win32 error " + err + ": " + win32ErrorMessage(err) + ")");
        }

        log.accept("Committing resource changes...");
        if (!Kernel32Ext.INSTANCE.EndUpdateResourceW(hUpdate, false)) {
            int err = Native.getLastError();
            throw new IOException("EndUpdateResource (commit) failed for " + outputExe
                    + " (Win32 error " + err + ": " + win32ErrorMessage(err) + ")");
        }
    }

    /**
     * Opens the resource-update handle on the freshly-copied exe, retrying a
     * few times with a short backoff. Immediately after Files.copy() writes a
     * new .exe, it's common on Windows for antivirus real-time protection (or,
     * if the output folder is under OneDrive/Desktop, the cloud-sync filter
     * driver) to transiently hold the file such that BeginUpdateResource sees
     * it as ERROR_FILE_NOT_FOUND (2) or ERROR_ACCESS_DENIED (5) for a few
     * hundred milliseconds even though it plainly exists on disk. A short
     * retry loop absorbs that race without masking a genuine problem.
     */
    private Pointer beginUpdateResourceWithRetry(File outputExe) throws IOException {
        final int maxAttempts = 6;
        final long backoffMillis = 150;

        if (!outputExe.isFile() || outputExe.length() == 0) {
            throw new IOException("BeginUpdateResource precheck failed: " + outputExe
                    + " does not exist or is empty right after being copied. If this folder is under "
                    + "OneDrive (e.g. Desktop) or is scanned by antivirus/Controlled Folder Access, try "
                    + "building to a plain local folder instead (e.g. C:\\Wraptor\\output).");
        }

        WString wPath = new WString(outputExe.getAbsolutePath());
        int lastErr = 0;
        for (int attempt = 1; attempt <= maxAttempts; attempt++) {
            Pointer hUpdate = Kernel32Ext.INSTANCE.BeginUpdateResourceW(wPath, false);
            if (hUpdate != null) {
                return hUpdate;
            }
            lastErr = Native.getLastError();
            // Only these two are worth retrying - they're the ones caused by a
            // transient lock/scan race, not a real structural problem.
            if (lastErr != 2 && lastErr != 5) {
                break;
            }
            try {
                Thread.sleep(backoffMillis);
            } catch (InterruptedException ie) {
                Thread.currentThread().interrupt();
                break;
            }
        }

        throw new IOException("BeginUpdateResource failed for " + outputExe
                + " after " + maxAttempts + " attempts (Win32 error " + lastErr + ": "
                + win32ErrorMessage(lastErr) + ")");
    }

    private boolean writeResource(Pointer hUpdate, String name, byte[] data, short language) {
        return writeResourceNamed(hUpdate, RT_RCDATA, name, data, language);
    }

    private boolean writeResourceNamed(Pointer hUpdate, int resourceType, String name, byte[] data, short language) {
        Memory mem = new Memory(data.length == 0 ? 1 : data.length);
        mem.write(0, data, 0, data.length);
        return Kernel32Ext.INSTANCE.UpdateResourceW(
                hUpdate,
                Kernel32Ext.makeIntResource(resourceType),
                new WString(name),
                language,
                mem,
                data.length
        );
    }

    private boolean writeResourceById(Pointer hUpdate, int resourceType, int id, byte[] data, short language) {
        Memory mem = new Memory(data.length == 0 ? 1 : data.length);
        mem.write(0, data, 0, data.length);
        return Kernel32Ext.INSTANCE.UpdateResourceW(
                hUpdate,
                Kernel32Ext.makeIntResource(resourceType),
                Kernel32Ext.makeIntResource(id),
                language,
                mem,
                data.length
        );
    }

    /**
     * Reads back the language ID the stub's own CONFIG resource was
     * compiled under, by loading the pristine stub as a resource-only data
     * file (never executed) and enumerating. CONFIG is guaranteed to exist
     * in every stub (native-stub/resource.rc always defines it), so it's a
     * reliable stand-in for "whatever language windres used for this
     * build" - in practice every resource in a single-LANGUAGE-block .rc
     * compiles under the same one.
     *
     * Falls back to LANG_NEUTRAL if discovery fails for any reason - that
     * matches the old (buggy) behavior rather than crashing the build, on
     * the theory that a stub compiled to genuinely use neutral language
     * should still work correctly with this fallback.
     */
    private short discoverStubLanguage(File stubTemplate) {
        Pointer hModule = Kernel32Ext.INSTANCE.LoadLibraryExW(
                new WString(stubTemplate.getAbsolutePath()),
                null,
                Kernel32Ext.LOAD_LIBRARY_AS_DATAFILE | Kernel32Ext.LOAD_LIBRARY_AS_IMAGE_RESOURCE);
        if (hModule == null) {
            return LANG_NEUTRAL;
        }
        try {
            short[] found = {LANG_NEUTRAL};
            boolean[] any = {false};
            Kernel32Ext.EnumResLangCallback callback = (hMod, type, name, language, lParam) -> {
                found[0] = language;
                any[0] = true;
                return false; // one is enough, stop enumerating
            };
            Kernel32Ext.INSTANCE.EnumResourceLanguagesW(
                    hModule, Kernel32Ext.makeIntResource(RT_RCDATA), new WString("CONFIG"), callback, null);
            return any[0] ? found[0] : LANG_NEUTRAL;
        } finally {
            Kernel32Ext.INSTANCE.FreeLibrary(hModule);
        }
    }

    /**
     * Reads back what identifier(s) - numeric ID or string name - the
     * stub's own resource.rc actually gave a resource of the given type,
     * by loading the pristine stub as a resource-only data file (never
     * executed) and enumerating. This avoids the mistake of assuming a
     * fixed name/ID: if the assumption is wrong, UpdateResource happily
     * adds a brand new, unreferenced resource instead of replacing the
     * real one - the file keeps looking untouched with no error reported
     * anywhere. This is exactly the bug that hit the icon before this
     * method existed, generalized here to also cover RT_VERSION and
     * RT_MANIFEST.
     *
     * Failure here (LoadLibraryExW returning null, or zero results) is
     * treated as "unknown," not fatal - the caller falls back to a sane
     * default rather than aborting the whole build over it.
     */
    private List<Object> discoverExistingResourceIdentifiers(File stubTemplate, int resourceType) {
        List<Object> found = new ArrayList<>();
        Pointer hModule = Kernel32Ext.INSTANCE.LoadLibraryExW(
                new WString(stubTemplate.getAbsolutePath()),
                null,
                Kernel32Ext.LOAD_LIBRARY_AS_DATAFILE | Kernel32Ext.LOAD_LIBRARY_AS_IMAGE_RESOURCE);
        if (hModule == null) {
            return found;
        }
        try {
            Kernel32Ext.EnumResNameCallback callback = (hMod, type, name, lParam) -> {
                if (Kernel32Ext.isIntResource(name)) {
                    found.add(Kernel32Ext.intResourceValue(name));
                } else {
                    found.add(name.getWideString(0));
                }
                return true; // returning true keeps enumeration going
            };
            Kernel32Ext.INSTANCE.EnumResourceNamesW(
                    hModule, Kernel32Ext.makeIntResource(resourceType), callback, null);
        } finally {
            Kernel32Ext.INSTANCE.FreeLibrary(hModule);
        }
        return found;
    }

    /**
     * Embeds a custom .ico as the exe's application icon: one RT_ICON per
     * frame, plus the RT_GROUP_ICON directory that ties the frames
     * together (see {@link IconResourceBuilder} for that translation),
     * written under whichever identifier(s) {@link #discoverExistingIconGroupIdentifiers}
     * found on the pristine stub - so this replaces the real default icon
     * group instead of guessing its name and adding a second, inert one.
     * If discovery found nothing (should only happen if a stub somehow
     * ships with no icon at all), falls back to numeric ID 1, the
     * conventional "main icon" slot most toolchains use.
     */
    private boolean writeIcon(Pointer hUpdate, File icoFile, List<Object> existingGroupIds, short language) throws IOException {
        IconResourceBuilder icon;
        try {
            icon = IconResourceBuilder.parse(icoFile);
        } catch (IOException e) {
            throw new IOException("Could not read icon file " + icoFile.getName() + ": " + e.getMessage(), e);
        }

        boolean ok = true;
        for (IconResourceBuilder.Frame frame : icon.frames) {
            ok &= writeResourceById(hUpdate, IconResourceBuilder.RT_ICON, frame.id, frame.data, language);
        }

        List<Object> targets = existingGroupIds.isEmpty()
                ? Collections.singletonList(1)
                : existingGroupIds;
        log.accept("  Icon group target(s) in stub: " + targets
                + (existingGroupIds.isEmpty() ? " (none discovered, using fallback ID 1)" : " (discovered)"));
        for (Object id : targets) {
            if (id instanceof Integer) {
                ok &= writeResourceById(hUpdate, IconResourceBuilder.RT_GROUP_ICON, (Integer) id, icon.groupDirectory, language);
            } else {
                ok &= writeResourceNamed(hUpdate, IconResourceBuilder.RT_GROUP_ICON, (String) id, icon.groupDirectory, language);
            }
        }
        log.accept("  Icon embedded successfully (" + icon.frames.size() + " frame(s)).");
        return ok;
    }

    /**
     * Writes the RT_VERSION resource (Explorer's Details tab: File version,
     * Product version, Company name, File description, Copyright) from the
     * EXE Info tab fields. Always runs, even with default/empty fields,
     * since {@link ProjectConfig} already ships sane version defaults -
     * there's no meaningful "off" state to special-case.
     */
    private boolean writeVersionInfo(Pointer hUpdate, ProjectConfig config, List<Object> existingIds, short language) {
        byte[] versionBytes = VersionInfoBuilder.build(
                config.fileVersion,
                config.productVersion,
                config.companyName,
                config.fileDescription,
                config.copyright,
                config.applicationName,   // ProductName
                config.applicationName,   // InternalName
                config.applicationName + ".exe" // OriginalFilename
        );

        List<Object> targets = existingIds.isEmpty() ? Collections.singletonList(1) : existingIds;
        log.accept("  Version-info target(s) in stub: " + targets
                + (existingIds.isEmpty() ? " (none discovered, using fallback ID 1)" : " (discovered)"));

        boolean ok = true;
        for (Object id : targets) {
            if (id instanceof Integer) {
                ok &= writeResourceById(hUpdate, RT_VERSION, (Integer) id, versionBytes, language);
            } else {
                ok &= writeResourceNamed(hUpdate, RT_VERSION, (String) id, versionBytes, language);
            }
        }
        return ok;
    }

    /**
     * Swaps the stub's default asInvoker manifest for one requesting
     * requireAdministrator (a UAC prompt on launch), written under
     * whichever identifier the pristine stub's own RT_MANIFEST actually
     * uses. When "Request administrator privileges" is unchecked, this is
     * never called - every build starts from a fresh copy of the pristine
     * embedded stub, so the untouched default (asInvoker) manifest already
     * applies with no write needed.
     */
    private boolean writeAdminManifest(Pointer hUpdate, List<Object> existingIds, short language) {
        byte[] manifestBytes = ADMIN_MANIFEST_XML.getBytes(java.nio.charset.StandardCharsets.UTF_8);

        List<Object> targets = existingIds.isEmpty() ? Collections.singletonList(1) : existingIds;
        log.accept("  Manifest target(s) in stub: " + targets
                + (existingIds.isEmpty() ? " (none discovered, using fallback ID 1)" : " (discovered)"));

        boolean ok = true;
        for (Object id : targets) {
            if (id instanceof Integer) {
                ok &= writeResourceById(hUpdate, RT_MANIFEST, (Integer) id, manifestBytes, language);
            } else {
                ok &= writeResourceNamed(hUpdate, RT_MANIFEST, (String) id, manifestBytes, language);
            }
        }
        return ok;
    }

    /** Mirrors native-stub/app.manifest exactly, except requestedExecutionLevel swapped to requireAdministrator. */
    private static final String ADMIN_MANIFEST_XML =
            "<?xml version=\"1.0\" encoding=\"UTF-8\" standalone=\"yes\"?>\n"
            + "<assembly xmlns=\"urn:schemas-microsoft-com:asm.v1\" manifestVersion=\"1.0\">\n"
            + "  <trustInfo xmlns=\"urn:schemas-microsoft-com:asm.v3\">\n"
            + "    <security>\n"
            + "      <requestedPrivileges>\n"
            + "        <requestedExecutionLevel level=\"requireAdministrator\" uiAccess=\"false\"/>\n"
            + "      </requestedPrivileges>\n"
            + "    </security>\n"
            + "  </trustInfo>\n"
            + "  <compatibility xmlns=\"urn:schemas-microsoft-com:compatibility.v1\">\n"
            + "    <application>\n"
            + "      <!-- Win7 -->\n"
            + "      <supportedOS Id=\"{35138b9a-5d96-4fbd-8e2d-a2440225f93a}\"/>\n"
            + "      <!-- Win8/8.1 -->\n"
            + "      <supportedOS Id=\"{4a2f28e3-53b9-4441-ba9c-d69d4a4a6e38}\"/>\n"
            + "      <!-- Win10/11 -->\n"
            + "      <supportedOS Id=\"{8e0f7a12-bfb3-4fe8-b9a5-48fd50a15a9a}\"/>\n"
            + "    </application>\n"
            + "  </compatibility>\n"
            + "</assembly>\n";

    /** Translates the handful of Win32 error codes that actually show up here into plain English. */
    private static String win32ErrorMessage(int code) {
        switch (code) {
            case 0:
                return "no Win32 error was reported - the failure likely isn't from the OS call itself";
            case 2:
                return "ERROR_FILE_NOT_FOUND - reported even though the file exists on disk. After several "
                        + "retries this almost always means antivirus real-time scanning or a cloud-sync "
                        + "filter driver (OneDrive, common on Desktop/Documents) is holding an exclusive lock "
                        + "on the freshly-copied exe. Try building to a plain local folder outside OneDrive "
                        + "(e.g. C:\\Wraptor\\output), or add an antivirus exclusion for the output folder.";
            case 3:
                return "ERROR_PATH_NOT_FOUND - the output folder doesn't exist";
            case 5:
                return "ERROR_ACCESS_DENIED - Windows blocked writing to that file. Very often this is "
                        + "Controlled Folder Access (Windows Security > Virus & threat protection > "
                        + "Ransomware protection), which by default blocks unrecognized apps from writing "
                        + "to the Desktop/Documents/Pictures folders. Either allow java.exe there, or build "
                        + "to a folder outside those protected locations.";
            case 32:
                return "ERROR_SHARING_VIOLATION - the exe is open/locked by another process (a previous "
                        + "run still executing, antivirus real-time scanning it, or it's open in Explorer)";
            case 1392:
                return "ERROR_FILE_CORRUPT - the stub exe copy isn't a well-formed PE file";
            default:
                return "see Microsoft's Win32 System Error Codes reference for code " + code;
        }
    }
}