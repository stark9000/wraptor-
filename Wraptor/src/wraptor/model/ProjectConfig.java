package wraptor.model;

import java.io.File;
import java.util.ArrayList;
import java.util.List;

/**
 * Plain data model bound to every tab in the builder GUI.
 *
 * Some fields get set programmatically from a tab other than the one that
 * displays them (e.g. JarsPanel auto-detecting the main class and deriving
 * the app name). {@link #addChangeListener} lets the owning panel refresh
 * its own widget when that happens instead of going stale.
 */
public class ProjectConfig {

    private final List<Runnable> changeListeners = new ArrayList<>();

    /** Registers a callback fired after any programmatic (non-widget) field change. */
    public void addChangeListener(Runnable listener) {
        changeListeners.add(listener);
    }

    /** Call after changing a field from code that isn't the field's own bound widget. */
    public void fireChanged() {
        for (Runnable r : changeListeners) {
            r.run();
        }
    }

    // ---- Application tab ----
    public String applicationName = "";
    public String outputDirectory = "";
    public File iconFile;                 // .ico
    public boolean compatXP = false;
    public boolean compatVista = false;
    public boolean compat7 = true;
    public boolean compat8_1 = true;
    public boolean compat10_11 = true;
    public boolean requestAdminPrivileges = false;
    public boolean singleInstance = true;
    public boolean buildX86 = false;
    public boolean buildX64 = true;

    // ---- Java tab ----
    public String mainClass = "";
    // -Dfile.encoding=UTF-8 is a safe, broadly-applicable default: it makes text
    // handling consistent regardless of the machine's system locale (a common
    // source of "works on my machine" bugs for Swing apps), without changing
    // observable behavior for the vast majority of applications.
    public String jvmArguments = "-Dfile.encoding=UTF-8";
    public String jreMin = "1.8";
    public String jreMax = "";

    // ---- JARs tab ----
    public static class JarEntry {

        public File file;
        public boolean isMain;

        public JarEntry(File file, boolean isMain) {
            this.file = file;
            this.isMain = isMain;
        }

        public long size() {
            return file.length();
        }
    }
    public final List<JarEntry> jars = new ArrayList<>();

    // ---- EXE Info tab ----
    public String fileVersion = "1.0.0.0";
    public String productVersion = "1.0.0.0";
    public String companyName = "";
    public String fileDescription = "";
    public String copyright = "";

    // ---- derived helpers ----
    public JarEntry mainJar() {
        for (JarEntry j : jars) {
            if (j.isMain) {
                return j;
            }
        }
        return null;
    }

    public List<JarEntry> libJars() {
        List<JarEntry> result = new ArrayList<>();
        for (JarEntry j : jars) {
            if (!j.isMain) {
                result.add(j);
            }
        }
        return result;
    }

    public void setMainJar(JarEntry chosen) {
        for (JarEntry j : jars) {
            j.isMain = (j == chosen);
        }
    }

    /**
     * Serializes to the "key=value\n" CONFIG resource format the native stub
     * parses.
     */
    public byte[] toConfigBytes() {
        JarEntry main = mainJar();
        StringBuilder sb = new StringBuilder();
        sb.append("appName=").append(safe(applicationName)).append('\n');
        sb.append("mainClass=").append(safe(mainClass)).append('\n');
        sb.append("jvmArgs=").append(safe(jvmArguments)).append('\n');
        sb.append("jreMin=").append(safe(jreMin)).append('\n');
        sb.append("jreMax=").append(safe(jreMax)).append('\n');
        sb.append("singleInstance=").append(singleInstance ? 1 : 0).append('\n');
        sb.append("requestAdmin=").append(requestAdminPrivileges ? 1 : 0).append('\n');
        sb.append("mainJarName=").append(main != null ? main.file.getName() : "").append('\n');
        return sb.toString().getBytes(java.nio.charset.StandardCharsets.UTF_8);
    }

    private static String safe(String s) {
        // config format is key=value\n ; strip newlines/= from values defensively
        return s == null ? "" : s.replace("\n", " ").replace("\r", " ");
    }
}
