package wraptor.ui;

import javax.swing.*;
import javax.swing.filechooser.FileNameExtensionFilter;
import java.awt.*;
import java.awt.image.BufferedImage;
import java.io.File;
import wraptor.model.ProjectConfig;
import wraptor.resource.IcoDecoder;
import wraptor.util.ChooserPrefs;

public class ApplicationPanel extends JPanel {

    private static final String OUTPUT_DIR_CHOOSER_KEY = "outputDir";
    private static final String ICON_CHOOSER_KEY = "icon";
    private static final int ICON_PREVIEW_SIZE = 40;

    public ApplicationPanel(ProjectConfig config) {
        setLayout(new BorderLayout());
        setBackground(UiTheme.APP_BG);

        JPanel form = new JPanel(new GridBagLayout());
        GridBagConstraints c = new GridBagConstraints();
        c.fill = GridBagConstraints.HORIZONTAL;
        c.weightx = 1.0;
        c.insets = new Insets(4, 4, 4, 4);
        c.gridx = 0;
        int row = 0;

        addSectionTitle(form, c, row, "Application", "General settings for your Windows executable.");
        row += 3;

        // Application name
        c.gridy = row++;
        c.gridwidth = 2;
        c.gridx = 0;
        form.add(label("APPLICATION NAME"), c);
        c.gridy = row++;
        JTextField nameField = bindText(config.applicationName, v -> config.applicationName = v);
        form.add(nameField, c);
        // Application name can be filled in from another tab (JarsPanel derives it from
        // the main jar's file name) - refresh this field if that happens elsewhere.
        config.addChangeListener(() -> {
            String current = config.applicationName == null ? "" : config.applicationName;
            if (!nameField.getText().equals(current)) {
                nameField.setText(current);
            }
        });

        // Output directory
        c.gridy = row++;
        form.add(label("OUTPUT DIRECTORY"), c);
        c.gridy = row++;
        JPanel outDirRow = new JPanel(new BorderLayout(4, 0));
        outDirRow.setOpaque(false);
        JTextField outDirField = bindText(config.outputDirectory, v -> config.outputDirectory = v);
        JButton browseOut = UiTheme.secondaryButton("Browse");
        browseOut.addActionListener(e -> {
            JFileChooser fc = new JFileChooser();
            fc.setFileSelectionMode(JFileChooser.DIRECTORIES_ONLY);
            ChooserPrefs.applyLastDir(fc, OUTPUT_DIR_CHOOSER_KEY);
            if (fc.showOpenDialog(this) == JFileChooser.APPROVE_OPTION) {
                File chosen = fc.getSelectedFile();
                String path = chosen.getAbsolutePath();
                outDirField.setText(path);
                config.outputDirectory = path;
                ChooserPrefs.rememberLastDir(OUTPUT_DIR_CHOOSER_KEY, chosen);
            }
        });
        outDirRow.add(outDirField, BorderLayout.CENTER);
        outDirRow.add(browseOut, BorderLayout.EAST);
        form.add(outDirRow, c);

        // Icon
        c.gridy = row++;
        form.add(label("APPLICATION ICON (.ICO)"), c);
        c.gridy = row++;
        JPanel iconRow = new JPanel(new FlowLayout(FlowLayout.LEFT, 8, 0));
        iconRow.setOpaque(false);
        JLabel iconPreview = new JLabel();
        iconPreview.setHorizontalAlignment(SwingConstants.CENTER);
        iconPreview.setPreferredSize(new Dimension(44, 44));
        iconPreview.setOpaque(true);
        iconPreview.setBackground(UiTheme.APP_BG);
        iconPreview.setBorder(BorderFactory.createLineBorder(UiTheme.CARD_BORDER, 1));
        JLabel iconPathLabel = new JLabel(config.iconFile != null ? config.iconFile.getName() : "");
        iconPathLabel.setForeground(UiTheme.TEXT_MUTED);
        JButton chooseIcon = UiTheme.secondaryButton("Choose .ico");
        JButton clearIcon = UiTheme.dangerButton("Clear");
        chooseIcon.addActionListener(e -> {
            JFileChooser fc = new JFileChooser();
            fc.setFileFilter(new FileNameExtensionFilter("Icon files (*.ico)", "ico"));
            ChooserPrefs.applyLastDir(fc, ICON_CHOOSER_KEY);
            if (fc.showOpenDialog(this) == JFileChooser.APPROVE_OPTION) {
                config.iconFile = fc.getSelectedFile();
                iconPathLabel.setText(config.iconFile.getName());
                loadIconPreview(iconPreview, config.iconFile);
                ChooserPrefs.rememberLastDir(ICON_CHOOSER_KEY, config.iconFile);
            }
        });
        clearIcon.addActionListener(e -> {
            config.iconFile = null;
            iconPathLabel.setText("");
            iconPreview.setIcon(null);
            iconPreview.setText("");
            iconPreview.setToolTipText(null);
        });
        iconRow.add(iconPreview);
        iconRow.add(chooseIcon);
        iconRow.add(clearIcon);
        iconRow.add(iconPathLabel);
        form.add(iconRow, c);
        if (config.iconFile != null) {
            loadIconPreview(iconPreview, config.iconFile);
        }

        // Windows compatibility
        c.gridy = row++;
        form.add(sectionSubtitle("Windows compatibility"), c);
        c.gridy = row++;
        JPanel compatRow = new JPanel(new FlowLayout(FlowLayout.LEFT, 12, 0));
        compatRow.setOpaque(false);
        compatRow.add(checkbox("XP", config.compatXP, v -> config.compatXP = v));
        compatRow.add(checkbox("Vista", config.compatVista, v -> config.compatVista = v));
        compatRow.add(checkbox("7", config.compat7, v -> config.compat7 = v));
        compatRow.add(checkbox("8/8.1", config.compat8_1, v -> config.compat8_1 = v));
        compatRow.add(checkbox("10/11", config.compat10_11, v -> config.compat10_11 = v));
        form.add(compatRow, c);

        // Target architecture
        c.gridy = row++;
        form.add(sectionSubtitle("Target architecture"), c);
        c.gridy = row++;
        JPanel archRow = new JPanel(new FlowLayout(FlowLayout.LEFT, 12, 0));
        archRow.setOpaque(false);
        JCheckBox x86Box = checkbox("32-bit (x86)", config.buildX86, v -> config.buildX86 = v);
        JCheckBox x64Box = checkbox("64-bit (x64)", config.buildX64, v -> config.buildX64 = v);
        // guard against unchecking both -> re-check the other one so a build target always exists
        x86Box.addActionListener(e -> {
            if (!x86Box.isSelected() && !x64Box.isSelected()) {
                x64Box.setSelected(true);
                config.buildX64 = true;
            }
        });
        x64Box.addActionListener(e -> {
            if (!x64Box.isSelected() && !x86Box.isSelected()) {
                x86Box.setSelected(true);
                config.buildX86 = true;
            }
        });
        archRow.add(x86Box);
        archRow.add(x64Box);
        form.add(archRow, c);
        c.gridy = row++;
        JLabel archHint = new JLabel("Building both produces two files: AppName-x86.exe and AppName-x64.exe.");
        archHint.setForeground(UiTheme.TEXT_MUTED);
        form.add(archHint, c);

        // Privileges
        c.gridy = row++;
        form.add(sectionSubtitle("Privileges"), c);
        c.gridy = row++;
        form.add(checkbox("Request administrator privileges (UAC prompt on launch)",
                config.requestAdminPrivileges, v -> config.requestAdminPrivileges = v), c);
        c.gridy = row++;
        form.add(checkbox("Allow only one running instance at a time",
                config.singleInstance, v -> config.singleInstance = v), c);

        add(UiTheme.card(form), BorderLayout.CENTER);
    }

    /**
     * Renders a preview of the chosen .ico file. Java's ImageIO has no built-in
     * ICO reader, and most .ico frames aren't PNG-wrapped anyway - they're a raw
     * Windows DIB (BITMAPINFOHEADER + packed pixels + AND mask), so this goes
     * through {@link IcoDecoder}, which handles both cases.
     */
    private void loadIconPreview(JLabel target, File icoFile) {
        try {
            BufferedImage img = IcoDecoder.decode(icoFile);
            Image scaled = img.getScaledInstance(ICON_PREVIEW_SIZE, ICON_PREVIEW_SIZE, Image.SCALE_SMOOTH);
            target.setIcon(new ImageIcon(scaled));
            target.setText("");
            target.setToolTipText(img.getWidth() + "x" + img.getHeight() + " frame chosen from " + icoFile.getName());
        } catch (Exception ex) {
            target.setIcon(null);
            target.setText("?");
            target.setToolTipText("Could not read this .ico file: " + ex.getMessage());
        }
    }

    // ---- shared small helpers reused by the other panels ----
    static void addSectionTitle(JPanel panel, GridBagConstraints c, int row, String title, String subtitle) {
        c.gridy = row;
        JLabel t = new JLabel(title);
        t.setFont(t.getFont().deriveFont(Font.BOLD, 19f));
        t.setForeground(UiTheme.TEXT_PRIMARY);
        panel.add(t, c);
        c.gridy = row + 1;
        JLabel s = new JLabel(subtitle);
        s.setForeground(UiTheme.TEXT_MUTED);
        s.setBorder(BorderFactory.createEmptyBorder(2, 0, 10, 0));
        panel.add(s, c);
        c.gridy = row + 2;
        JSeparator sep = new JSeparator();
        sep.setForeground(UiTheme.CARD_BORDER);
        panel.add(sep, c);
    }

    static JLabel label(String text) {
        JLabel l = new JLabel(text);
        l.setFont(l.getFont().deriveFont(Font.BOLD, 11f));
        l.setForeground(UiTheme.LABEL_MUTED);
        return l;
    }

    static JLabel sectionSubtitle(String text) {
        JLabel l = new JLabel(text);
        l.setFont(l.getFont().deriveFont(Font.BOLD, 12f));
        l.setForeground(UiTheme.TEXT_PRIMARY);
        l.setBorder(BorderFactory.createEmptyBorder(10, 0, 2, 0));
        return l;
    }

    interface StringSetter {

        void set(String v);
    }

    interface BoolSetter {

        void set(boolean v);
    }

    static JTextField bindText(String initial, StringSetter setter) {
        JTextField field = new JTextField(initial);
        field.getDocument().addDocumentListener(new SimpleDocListener(() -> setter.set(field.getText())));
        return field;
    }

    static JCheckBox checkbox(String text, boolean initial, BoolSetter setter) {
        JCheckBox cb = UiTheme.checkbox(text, initial);
        cb.addActionListener(e -> setter.set(cb.isSelected()));
        return cb;
    }
}
