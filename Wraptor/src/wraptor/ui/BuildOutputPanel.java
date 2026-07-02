package wraptor.ui;

import javax.swing.*;
import javax.swing.text.*;
import java.awt.*;
import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Date;
import java.util.List;
import wraptor.model.ProjectConfig;
import wraptor.resource.EmbeddedStubs;
import wraptor.resource.ResourcePatcher;

public class BuildOutputPanel extends JPanel {

    /**
     * Thrown for pre-flight validation problems (missing/invalid app name,
     * main jar, output dir, main class, etc.) - things the person needs to
     * go fix on another tab, not a bug. Caught separately in {@link #runBuild}
     * so these show as a single clean log line instead of a full stack trace,
     * which otherwise reads exactly like an unhandled crash for what's really
     * just "you forgot to fill something in".
     */
    private static class ValidationException extends IOException {
        ValidationException(String message) {
            super(message);
        }
    }

    private final ProjectConfig config;
    private final JTextPane log = new JTextPane();
    private final JProgressBar progress = new JProgressBar();
    private final StyledDocument doc;
    private final Style normalStyle;
    private final Style successStyle;
    private final Style errorStyle;
    private final Style headerStyle;

    private int buildCount = 0;
    private volatile boolean building = false;
    private File lastOutputDir;
    private final JButton openFolderButton = UiTheme.secondaryButton("Open Output Folder");

    public BuildOutputPanel(ProjectConfig config) {
        this.config = config;
        setLayout(new BorderLayout());
        setBackground(UiTheme.APP_BG);
        setBorder(BorderFactory.createEmptyBorder(20, 24, 20, 24));

        JPanel titleBlock = new JPanel();
        titleBlock.setOpaque(false);
        titleBlock.setLayout(new BoxLayout(titleBlock, BoxLayout.Y_AXIS));

        JPanel titleRow = new JPanel(new BorderLayout());
        titleRow.setOpaque(false);
        titleRow.setAlignmentX(Component.LEFT_ALIGNMENT);
        JLabel title = new JLabel("Build Output");
        title.setFont(title.getFont().deriveFont(Font.BOLD, 19f));
        title.setForeground(UiTheme.TEXT_PRIMARY);
        titleRow.add(title, BorderLayout.WEST);

        openFolderButton.setVisible(false);
        openFolderButton.addActionListener(e -> openOutputFolder());
        titleRow.add(openFolderButton, BorderLayout.EAST);

        titleBlock.add(titleRow);
        titleBlock.add(Box.createVerticalStrut(8));

        progress.setIndeterminate(true);
        progress.setVisible(false);
        progress.setAlignmentX(Component.LEFT_ALIGNMENT);
        progress.setMaximumSize(new Dimension(Integer.MAX_VALUE, 6));
        progress.setPreferredSize(new Dimension(100, 6));
        progress.setBorderPainted(false);
        titleBlock.add(progress);
        titleBlock.add(Box.createVerticalStrut(8));

        add(titleBlock, BorderLayout.NORTH);

        log.setEditable(false);
        log.setFont(new Font(Font.MONOSPACED, Font.PLAIN, 12));
        log.setBackground(Color.WHITE);
        log.setBorder(BorderFactory.createEmptyBorder(10, 12, 10, 12));
        doc = log.getStyledDocument();

        normalStyle = log.addStyle("normal", null);
        StyleConstants.setForeground(normalStyle, UiTheme.TEXT_PRIMARY);

        successStyle = log.addStyle("success", normalStyle);
        StyleConstants.setForeground(successStyle, UiTheme.SUCCESS);
        StyleConstants.setBold(successStyle, true);

        errorStyle = log.addStyle("error", normalStyle);
        StyleConstants.setForeground(errorStyle, UiTheme.ERROR);
        StyleConstants.setBold(errorStyle, true);

        headerStyle = log.addStyle("header", normalStyle);
        StyleConstants.setForeground(headerStyle, UiTheme.TEXT_MUTED);
        StyleConstants.setItalic(headerStyle, true);

        JScrollPane scroll = new JScrollPane(log);
        scroll.setBorder(BorderFactory.createLineBorder(UiTheme.CARD_BORDER, 1));
        add(scroll, BorderLayout.CENTER);
    }

    private void appendLog(String line) {
        appendStyled(line, normalStyle);
    }

    private void appendStyled(String line, Style style) {
        SwingUtilities.invokeLater(() -> {
            try {
                doc.insertString(doc.getLength(), line + "\n", style);
            } catch (BadLocationException ignored) {
            }
            log.setCaretPosition(doc.getLength());
        });
    }

    /**
     * Kicks off the build on a background thread so the UI doesn't freeze.
     * A build already running is ignored - this is a defensive backstop;
     * the trigger button is also disabled by the caller for the duration.
     *
     * @param onFinished called on the EDT once the build finishes (success or
     *                    failure) so the caller can, e.g., re-enable its button.
     */
    public void runBuild(Component parent, Runnable onFinished) {
        if (building) {
            return;
        }
        building = true;
        buildCount++;

        SwingUtilities.invokeLater(() -> {
            log.setText("");
            progress.setVisible(true);
            openFolderButton.setVisible(false);
            String stamp = new SimpleDateFormat("HH:mm:ss").format(new Date());
            appendStyled("Build #" + buildCount + " \u2014 started " + stamp, headerStyle);
            appendStyled("----------------------------------------", headerStyle);
        });

        new SwingWorker<List<File>, Void>() {
            @Override
            protected List<File> doInBackground() throws Exception {
                return doBuild();
            }

            @Override
            protected void done() {
                progress.setVisible(false);
                try {
                    List<File> outputs = get();
                    appendLog("");
                    appendStyled("========================================", headerStyle);
                    appendStyled("BUILD SUCCEEDED", successStyle);
                    for (File f : outputs) {
                        appendLog("  -> " + f.getAbsolutePath());
                    }
                    appendStyled("========================================", headerStyle);
                    lastOutputDir = new File(config.outputDirectory);
                    openFolderButton.setVisible(true);
                } catch (Exception ex) {
                    Throwable cause = ex.getCause() != null ? ex.getCause() : ex;
                    appendLog("");
                    appendStyled("========================================", headerStyle);
                    if (cause instanceof ValidationException) {
                        appendStyled("BUILD STOPPED: " + cause.getMessage(), errorStyle);
                    } else {
                        appendStyled("BUILD FAILED: " + cause.getMessage(), errorStyle);
                        appendStyled("----------------------------------------", headerStyle);
                        appendLog("Full error details:");
                        appendStackTrace(cause);
                    }
                    appendStyled("========================================", headerStyle);
                } finally {
                    building = false;
                    if (onFinished != null) {
                        onFinished.run();
                    }
                }
            }
        }.execute();
    }

    /** Opens {@link #lastOutputDir} in the OS file browser. Desktop.open() is preferred since it's
     *  cross-platform-correct, but some minimal/embedded JREs don't implement it, so fall back to
     *  invoking explorer.exe directly rather than leaving the button silently do nothing. */
    private void openOutputFolder() {
        if (lastOutputDir == null || !lastOutputDir.isDirectory()) {
            return;
        }
        try {
            if (Desktop.isDesktopSupported() && Desktop.getDesktop().isSupported(Desktop.Action.OPEN)) {
                Desktop.getDesktop().open(lastOutputDir);
            } else {
                new ProcessBuilder("explorer.exe", lastOutputDir.getAbsolutePath()).start();
            }
        } catch (IOException ex) {
            appendStyled("Could not open output folder: " + ex.getMessage(), errorStyle);
        }
    }

    /** Logs every exception in the cause chain with its full stack trace, not just the top-level message. */
    private void appendStackTrace(Throwable t) {
        Throwable current = t;
        while (current != null) {
            appendLog(current.getClass().getName() + ": " + current.getMessage());
            for (StackTraceElement el : current.getStackTrace()) {
                appendLog("    at " + el);
            }
            current = current.getCause();
            if (current != null) {
                appendLog("Caused by:");
            }
        }
    }

    private static final java.util.regex.Pattern INVALID_FILENAME_CHARS =
            java.util.regex.Pattern.compile("[\\\\/:*?\"<>|]");
    // dotted Java identifier, e.g. "com.example.Main" - each segment must start
    // with a letter/_/$ and contain only letters/digits/_/$ after that
    private static final java.util.regex.Pattern VALID_CLASS_NAME =
            java.util.regex.Pattern.compile("^[A-Za-z_$][A-Za-z0-9_$]*(\\.[A-Za-z_$][A-Za-z0-9_$]*)*$");

    private List<File> doBuild() throws IOException {
        appendLog("Validating project...");

        // 1. Main jar - checked first since nothing downstream (config summary,
        //    resource building) is meaningful without one, and every field below
        //    that gets auto-filled from the main jar depends on it existing.
        if (config.jars.isEmpty()) {
            throw new ValidationException("No JARs added \u2014 add at least one jar on the JARs tab.");
        }
        if (config.mainJar() == null) {
            throw new ValidationException("No main jar selected \u2014 on the JARs tab, right-click a jar and "
                    + "choose 'Set as Main Jar' (auto-detected automatically if only one of your jars "
                    + "declares a Main-Class).");
        }

        // 2. Application name - required, and used directly as part of the output
        //    .exe filename, so also reject characters Windows won't allow there.
        if (config.applicationName == null || config.applicationName.trim().isEmpty()) {
            throw new ValidationException("Application name is required (Application tab).");
        }
        if (INVALID_FILENAME_CHARS.matcher(config.applicationName).find()) {
            throw new ValidationException("Application name \"" + config.applicationName + "\" contains "
                    + "characters that aren't allowed in a Windows file name (\\ / : * ? \" < > |) "
                    + "\u2014 fix it on the Application tab.");
        }

        // 3. Output directory - required, and must actually be usable: try to
        //    create it if it doesn't exist yet, and confirm it's writable.
        if (config.outputDirectory == null || config.outputDirectory.trim().isEmpty()) {
            throw new ValidationException("Output directory is required (Application tab).");
        }
        File outDir = new File(config.outputDirectory);
        if (!outDir.isDirectory() && !outDir.mkdirs() && !outDir.isDirectory()) {
            throw new ValidationException("Output directory \"" + config.outputDirectory + "\" doesn't exist "
                    + "and couldn't be created \u2014 check the path on the Application tab.");
        }
        if (!outDir.canWrite()) {
            throw new ValidationException("Output directory \"" + config.outputDirectory + "\" isn't writable "
                    + "\u2014 choose a different folder on the Application tab.");
        }

        // 4. Main class - required, and must at least look like a real
        //    dotted Java class name (e.g. com.example.Main).
        if (config.mainClass == null || config.mainClass.trim().isEmpty()) {
            throw new ValidationException("Main class is required (Java tab).");
        }
        if (!VALID_CLASS_NAME.matcher(config.mainClass.trim()).matches()) {
            throw new ValidationException("Main class \"" + config.mainClass + "\" doesn't look like a valid "
                    + "Java class name (expected something like com.example.Main) \u2014 fix it on the "
                    + "Java tab.");
        }

        // 5. At least one target architecture.
        if (!config.buildX86 && !config.buildX64) {
            throw new ValidationException("Select at least one target architecture (Application tab).");
        }

        appendLog("  OK");

        appendLog("");
        appendLog("Project summary:");
        appendLog("  Main jar:   " + config.mainJar().file.getName());
        appendLog("  Lib jars:   " + config.libJars().size());
        appendLog("  Main class: " + config.mainClass);
        appendLog("  Icon:       " + (config.iconFile != null ? config.iconFile.getName() : "(default)"));
        appendLog("  Output dir: " + config.outputDirectory);

        boolean buildingBoth = config.buildX86 && config.buildX64;
        List<File> outputs = new ArrayList<>();

        if (config.buildX86) {
            appendLog("");
            appendLog("--- Building x86 ---");
            outputs.add(buildOneArch("stub32.exe", EmbeddedStubs.stub32(), buildingBoth ? "-x86" : ""));
        }
        if (config.buildX64) {
            appendLog("");
            appendLog("--- Building x64 ---");
            outputs.add(buildOneArch("stub64.exe", EmbeddedStubs.stub64(), buildingBoth ? "-x64" : ""));
        }

        appendLog("");
        appendLog("Icon, version info, and admin-manifest (if requested) are embedded above per architecture.");

        return outputs;
    }

    private File buildOneArch(String archName, byte[] stubBytes, String fileSuffix) throws IOException {
        appendLog("Writing embedded " + archName + " stub to a temp file...");
        File stub = writeEmbeddedStubToTemp(archName, stubBytes);

        File outputExe = new File(config.outputDirectory, config.applicationName + fileSuffix + ".exe");
        appendLog("Copying stub to " + outputExe.getName() + " ...");
        appendLog("Patching resources into " + outputExe.getName() + " ...");

        ResourcePatcher patcher = new ResourcePatcher(this::appendLog);
        patcher.build(stub, outputExe, config);

        appendLog("Done: " + outputExe.getAbsolutePath());
        return outputExe;
    }

    /**
     * Writes one of the embedded stub byte arrays out to a temp file, since
     * BeginUpdateResource (a Win32 API) only works on files on disk, not
     * in-memory buffers. The stub bytes themselves are baked straight into
     * EmbeddedStubs.class - no classpath resource file involved, so this
     * always works regardless of what the IDE's incremental build did or
     * didn't copy into build/classes.
     */
    private File writeEmbeddedStubToTemp(String archName, byte[] stubBytes) throws IOException {
        File tmp = File.createTempFile("wraptor-", "-" + archName + ".exe");
        tmp.deleteOnExit();
        Files.write(tmp.toPath(), stubBytes);
        return tmp;
    }
}
