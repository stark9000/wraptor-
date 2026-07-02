package wraptor.ui;

 
 
import javax.swing.*;
import javax.swing.table.AbstractTableModel;
import java.awt.*;
import java.awt.datatransfer.DataFlavor;
import java.awt.dnd.*;
import java.io.File;
import java.io.IOException;
import java.util.List;
import java.util.jar.JarFile;
import java.util.jar.Manifest;
import wraptor.model.ProjectConfig;

public class JarsPanel extends JPanel {

    private final ProjectConfig config;
    private final JarTableModel tableModel;
    private final JTable table;

    public JarsPanel(ProjectConfig config) {
        this.config = config;
        setLayout(new BorderLayout(0, 12));
        setBorder(BorderFactory.createEmptyBorder(20, 24, 20, 24));
        setBackground(UiTheme.APP_BG);

        JPanel header = new JPanel();
        header.setOpaque(false);
        header.setLayout(new BoxLayout(header, BoxLayout.Y_AXIS));
        JLabel title = new JLabel("JARs");
        title.setFont(title.getFont().deriveFont(Font.BOLD, 19f));
        title.setForeground(UiTheme.TEXT_PRIMARY);
        title.setAlignmentX(Component.LEFT_ALIGNMENT);
        JLabel subtitle = new JLabel("Add your application JARs and dependencies.");
        subtitle.setForeground(UiTheme.TEXT_MUTED);
        subtitle.setAlignmentX(Component.LEFT_ALIGNMENT);
        subtitle.setBorder(BorderFactory.createEmptyBorder(2, 0, 10, 0));
        header.add(title);
        header.add(subtitle);
        JSeparator sep = new JSeparator();
        sep.setForeground(UiTheme.CARD_BORDER);
        sep.setAlignmentX(Component.LEFT_ALIGNMENT);
        header.add(sep);
        add(header, BorderLayout.NORTH);

        tableModel = new JarTableModel(config);
        table = new JTable(tableModel);
        table.setRowHeight(26);
        table.setDropMode(DropMode.INSERT);
        table.setShowGrid(false);
        table.setIntercellSpacing(new Dimension(0, 0));
        table.setFillsViewportHeight(true);
        table.setSelectionBackground(UiTheme.ACCENT);
        table.setSelectionForeground(Color.WHITE);
        table.getTableHeader().setFont(table.getFont().deriveFont(Font.BOLD, 11f));
        table.getTableHeader().setForeground(UiTheme.LABEL_MUTED);
        table.getColumnModel().getColumn(0).setPreferredWidth(360);
        table.getColumnModel().getColumn(1).setPreferredWidth(80);
        table.getColumnModel().getColumn(2).setPreferredWidth(60);
        table.setDefaultRenderer(Object.class, new JarRoleRenderer());

        // right-click -> "Set as Main Jar"
        JPopupMenu popup = new JPopupMenu();
        JMenuItem setMain = new JMenuItem("Set as Main Jar");
        setMain.addActionListener(e -> {
            int row = table.getSelectedRow();
            if (row >= 0) {
                applyMainJar(config.jars.get(row));
                tableModel.fireTableDataChanged();
            }
        });
        popup.add(setMain);
        table.setComponentPopupMenu(popup);

        JScrollPane scroll = new JScrollPane(table);
        scroll.setBorder(BorderFactory.createLineBorder(UiTheme.CARD_BORDER, 1));
        scroll.getViewport().setBackground(Color.WHITE);
        scroll.setDropTarget(new DropTarget(scroll, new DnDHandler()));
        add(scroll, BorderLayout.CENTER);

        JPanel buttons = new JPanel(new FlowLayout(FlowLayout.LEFT, 8, 0));
        buttons.setOpaque(false);
        JButton addBtn = UiTheme.primaryButton("+ Add JARs");
        addBtn.addActionListener(e -> chooseAndAddJars());
        JButton removeBtn = UiTheme.dangerButton("Remove selected");
        removeBtn.addActionListener(e -> {
            int[] rows = table.getSelectedRows();
            java.util.Arrays.sort(rows);
            for (int i = rows.length - 1; i >= 0; i--) config.jars.remove(rows[i]);
            tableModel.fireTableDataChanged();
        });
        JButton clearBtn = UiTheme.dangerButton("Clear all");
        clearBtn.addActionListener(e -> {
            config.jars.clear();
            tableModel.fireTableDataChanged();
        });
        buttons.add(addBtn);
        buttons.add(removeBtn);
        buttons.add(clearBtn);
        add(buttons, BorderLayout.SOUTH);
    }

    /** Zebra-striped rows, with the "Main" role rendered in the accent color. */
    private static class JarRoleRenderer extends javax.swing.table.DefaultTableCellRenderer {
        @Override
        public Component getTableCellRendererComponent(JTable table, Object value, boolean isSelected,
                boolean hasFocus, int row, int column) {
            Component c = super.getTableCellRendererComponent(table, value, isSelected, hasFocus, row, column);
            if (!isSelected) {
                c.setBackground(row % 2 == 0 ? Color.WHITE : new Color(0xF6, 0xF7, 0xFA));
                c.setForeground(UiTheme.TEXT_PRIMARY);
            }
            if (column == 2 && "Main".equals(value) && !isSelected) {
                c.setForeground(UiTheme.ACCENT);
                setFont(getFont().deriveFont(Font.BOLD));
            } else {
                setFont(getFont().deriveFont(Font.PLAIN));
            }
            return c;
        }
    }

    private static final String CHOOSER_KEY = "jars";

    private void chooseAndAddJars() {
        JFileChooser fc = new JFileChooser();
        fc.setMultiSelectionEnabled(true);
        fc.setFileFilter(new javax.swing.filechooser.FileNameExtensionFilter("JAR files (*.jar)", "jar"));
        wraptor.util.ChooserPrefs.applyLastDir(fc, CHOOSER_KEY);
        if (fc.showOpenDialog(this) == JFileChooser.APPROVE_OPTION) {
            File[] chosen = fc.getSelectedFiles();
            if (chosen.length > 0) {
                wraptor.util.ChooserPrefs.rememberLastDir(CHOOSER_KEY, chosen[0]);
            }
            addJars(chosen);
        }
    }

    private void addJars(File[] files) {
        for (File f : files) addSingleJar(f);
        autoDetectMainJarIfUnset();
        tableModel.fireTableDataChanged();
    }

    private void addSingleJar(File f) {
        config.jars.add(new ProjectConfig.JarEntry(f, false));
    }

    /** If no main jar is set yet, and exactly one added jar declares a Main-Class, flag it as main. */
    private void autoDetectMainJarIfUnset() {
        if (config.mainJar() != null) return;

        ProjectConfig.JarEntry candidate = null;
        int candidateCount = 0;
        for (ProjectConfig.JarEntry entry : config.jars) {
            String mc = readMainClass(entry.file);
            if (mc != null) {
                candidateCount++;
                candidate = entry;
            }
        }
        if (candidateCount == 1) {
            applyMainJar(candidate);
        }
    }

    /**
     * Marks {@code entry} as the main jar, then fills in the Java-tab main
     * class (from the jar's manifest) and the Application-tab app name (from
     * the jar's file name) whenever those fields are still blank. Finishes
     * by notifying the other tabs via {@link ProjectConfig#fireChanged()} so
     * their text fields - already built and sitting in the CardLayout - pick
     * up the new values instead of showing stale text.
     */
    private void applyMainJar(ProjectConfig.JarEntry entry) {
        config.setMainJar(entry);

        if (config.mainClass == null || config.mainClass.isEmpty()) {
            String mc = readMainClass(entry.file);
            if (mc != null) {
                config.mainClass = mc;
            }
        }
        if (config.applicationName == null || config.applicationName.trim().isEmpty()) {
            config.applicationName = stripJarExtension(entry.file.getName());
        }
        config.fireChanged();
    }

    private static String stripJarExtension(String fileName) {
        return fileName.toLowerCase().endsWith(".jar")
                ? fileName.substring(0, fileName.length() - 4)
                : fileName;
    }

    private static String readMainClass(File jarFile) {
        try (JarFile jf = new JarFile(jarFile)) {
            Manifest mf = jf.getManifest();
            if (mf == null) return null;
            return mf.getMainAttributes().getValue("Main-Class");
        } catch (IOException e) {
            return null;
        }
    }

    private class DnDHandler extends DropTargetAdapter {
        @Override
        @SuppressWarnings("unchecked")
        public void drop(DropTargetDropEvent evt) {
            try {
                evt.acceptDrop(DnDConstants.ACTION_COPY);
                List<File> dropped = (List<File>) evt.getTransferable().getTransferData(DataFlavor.javaFileListFlavor);
                File[] jars = dropped.stream()
                        .filter(f -> f.getName().toLowerCase().endsWith(".jar"))
                        .toArray(File[]::new);
                addJars(jars);
            } catch (Exception ignored) {
            }
        }
    }

    private static class JarTableModel extends AbstractTableModel {
        private final ProjectConfig config;
        private final String[] columns = {"JAR File", "Size", "Role"};

        JarTableModel(ProjectConfig config) { this.config = config; }

        @Override public int getRowCount() { return config.jars.size(); }
        @Override public int getColumnCount() { return columns.length; }
        @Override public String getColumnName(int col) { return columns[col]; }

        @Override
        public Object getValueAt(int row, int col) {
            ProjectConfig.JarEntry e = config.jars.get(row);
            switch (col) {
                case 0: return e.file.getName();
                case 1: return humanSize(e.size());
                case 2: return e.isMain ? "Main" : "Lib";
                default: return "";
            }
        }

        private String humanSize(long bytes) {
            if (bytes < 1024) return bytes + " B";
            if (bytes < 1024 * 1024) return String.format("%.1f KB", bytes / 1024.0);
            return String.format("%.1f MB", bytes / (1024.0 * 1024));
        }
    }
}
