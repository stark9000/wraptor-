package wraptor.util;

import java.io.File;
import java.util.prefs.Preferences;
import javax.swing.JFileChooser;

/**
 * Remembers the last directory used by each {@link JFileChooser} in the app,
 * keyed by a short purpose string (e.g. "jars", "icon", "outputDir"), so
 * chooser dialogs reopen where the user left off last session instead of
 * always starting at the OS default (home dir on Windows).
 *
 * Backed by {@link Preferences}, which on Windows lives in the registry
 * under HKCU\Software\JavaSoft\Prefs - no config file to manage, survives
 * app restarts for free.
 */
public final class ChooserPrefs {

    private static final Preferences PREFS = Preferences.userNodeForPackage(ChooserPrefs.class);
    private static final String KEY_PREFIX = "lastDir.";

    private ChooserPrefs() {
    }

    /** Points {@code fc} at the remembered directory for {@code key}, if any. */
    public static void applyLastDir(JFileChooser fc, String key) {
        String path = PREFS.get(KEY_PREFIX + key, null);
        if (path != null) {
            File dir = new File(path);
            if (dir.isDirectory()) {
                fc.setCurrentDirectory(dir);
            }
        }
    }

    /** Remembers the containing directory of {@code fileOrDir} under {@code key}. */
    public static void rememberLastDir(String key, File fileOrDir) {
        if (fileOrDir == null) {
            return;
        }
        File dir = fileOrDir.isDirectory() ? fileOrDir : fileOrDir.getParentFile();
        if (dir != null) {
            PREFS.put(KEY_PREFIX + key, dir.getAbsolutePath());
        }
    }
}
