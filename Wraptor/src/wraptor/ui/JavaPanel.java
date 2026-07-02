package wraptor.ui;

 
import javax.swing.*;
import java.awt.*;
import wraptor.model.ProjectConfig;

public class JavaPanel extends JPanel {

    private static final String[] JRE_VERSIONS = {
            "", "1.8", "9", "10", "11", "12", "13", "14", "15", "16", "17", "18", "19", "20", "21", "22"
    };

    public JavaPanel(ProjectConfig config) {
        setLayout(new BorderLayout());
        setBackground(UiTheme.APP_BG);

        JPanel form = new JPanel(new GridBagLayout());
        GridBagConstraints c = new GridBagConstraints();
        c.fill = GridBagConstraints.HORIZONTAL;
        c.weightx = 1.0;
        c.insets = new Insets(4, 4, 4, 4);
        c.gridx = 0;
        int row = 0;

        ApplicationPanel.addSectionTitle(form, c, row, "Java", "JRE requirements, JVM arguments and toolchain.");
        row += 3;

        c.gridy = row++;
        form.add(ApplicationPanel.label("MAIN CLASS"), c);
        c.gridy = row++;
        JTextField mainClassField = ApplicationPanel.bindText(config.mainClass, v -> config.mainClass = v);
        form.add(mainClassField, c);
        // Main class can be filled in from another tab (JarsPanel auto-detects it from the
        // main jar's manifest) - refresh this field if that happens elsewhere.
        config.addChangeListener(() -> {
            String current = config.mainClass == null ? "" : config.mainClass;
            if (!mainClassField.getText().equals(current)) {
                mainClassField.setText(current);
            }
        });

        c.gridy = row++;
        form.add(ApplicationPanel.label("JVM ARGUMENTS (SEMICOLON-SEPARATED)"), c);
        c.gridy = row++;
        form.add(ApplicationPanel.bindText(config.jvmArguments, v -> config.jvmArguments = v), c);

        c.gridy = row++;
        form.add(ApplicationPanel.sectionSubtitle("JRE version requirement"), c);

        c.gridy = row++;
        JPanel jreRow = new JPanel(new FlowLayout(FlowLayout.LEFT, 8, 0));
        jreRow.setOpaque(false);
        jreRow.add(new JLabel("Minimum:"));
        JComboBox<String> minBox = new JComboBox<>(JRE_VERSIONS);
        minBox.setSelectedItem(config.jreMin);
        minBox.addActionListener(e -> config.jreMin = (String) minBox.getSelectedItem());
        jreRow.add(minBox);

        jreRow.add(new JLabel("Maximum:"));
        JComboBox<String> maxBox = new JComboBox<>(JRE_VERSIONS);
        maxBox.setSelectedItem(config.jreMax);
        maxBox.addActionListener(e -> config.jreMax = (String) maxBox.getSelectedItem());
        jreRow.add(maxBox);
        form.add(jreRow, c);

        add(UiTheme.card(form), BorderLayout.CENTER);
    }
}
