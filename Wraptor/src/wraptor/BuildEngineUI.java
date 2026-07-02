package wraptor;

import javax.swing.*;
import java.awt.*;
import wraptor.model.ProjectConfig;
import wraptor.ui.ApplicationPanel;
import wraptor.ui.BuildOutputPanel;
import wraptor.ui.ExeInfoPanel;
import wraptor.ui.JarsPanel;
import wraptor.ui.JavaPanel;
import wraptor.ui.UiTheme;

public class BuildEngineUI extends JFrame {

    private final ProjectConfig config = new ProjectConfig();
    private final CardLayout cardLayout = new CardLayout();
    private final JPanel cards = new JPanel(cardLayout);

    private static final String[] SECTIONS = {
        "JARs", "Application", "Java", "EXE Info"
    };

    public BuildEngineUI() {
        super("Wraptor - Build Engine");
        setDefaultCloseOperation(JFrame.EXIT_ON_CLOSE);
        setSize(860, 600);
        setMinimumSize(new Dimension(820, 520));
        setLocationRelativeTo(null);
        setLayout(new BorderLayout());

        JList<String> nav = new JList<>(SECTIONS);
        nav.setSelectedIndex(0);
        nav.setFixedCellHeight(38);
        nav.setBorder(BorderFactory.createEmptyBorder(10, 0, 10, 0));
        nav.setBackground(UiTheme.SIDEBAR_BG);
        nav.setCellRenderer(new NavCellRenderer());
        nav.setFocusable(true);
        nav.addListSelectionListener(e -> {
            if (!e.getValueIsAdjusting()) {
                cardLayout.show(cards, nav.getSelectedValue());
            }
        });
        JScrollPane navScroll = new JScrollPane(nav);
        navScroll.setBorder(BorderFactory.createEmptyBorder());
        navScroll.setPreferredSize(new Dimension(170, 0));
        navScroll.getViewport().setBackground(UiTheme.SIDEBAR_BG);

        BuildOutputPanel buildOutputPanel = new BuildOutputPanel(config);

        cards.setBackground(UiTheme.APP_BG);
        cards.add(new JarsPanel(config), "JARs");
        cards.add(new ApplicationPanel(config), "Application");
        cards.add(new JavaPanel(config), "Java");
        cards.add(new ExeInfoPanel(config), "EXE Info");

        JPanel main = new JPanel(new BorderLayout());
        main.setBackground(UiTheme.APP_BG);
        main.add(cards, BorderLayout.CENTER);

        JButton buildButton = UiTheme.primaryButton("Build EXE");
        buildButton.addActionListener(e -> {
            nav.setSelectedValue("Build Output", true);
            buildButton.setEnabled(false);
            buildOutputPanel.runBuild(this, () -> buildButton.setEnabled(true));
        });
        JPanel bottomBar = new JPanel(new FlowLayout(FlowLayout.RIGHT, 20, 14));
        bottomBar.setBackground(UiTheme.APP_BG);
        bottomBar.setBorder(BorderFactory.createMatteBorder(1, 0, 0, 0, UiTheme.CARD_BORDER));
        bottomBar.add(buildButton);
        main.add(bottomBar, BorderLayout.SOUTH);

        add(navScroll, BorderLayout.WEST);
        add(main, BorderLayout.CENTER);

        // "Build Output" gets its own nav slot too, shown after a build starts
        DefaultListModel<String> model = new DefaultListModel<>();
        for (String s : SECTIONS) {
            model.addElement(s);
        }
        model.addElement("Build Output");
        nav.setModel(model);
        cards.add(buildOutputPanel, "Build Output");
    }

    /**
     * Dark sidebar rows with a solid accent-colored highlight on the selected
     * item.
     */
    private static class NavCellRenderer extends DefaultListCellRenderer {

        @Override
        public Component getListCellRendererComponent(JList<?> list, Object value, int index,
                boolean isSelected, boolean cellHasFocus) {
            JLabel l = (JLabel) super.getListCellRendererComponent(list, value, index, isSelected, false);
            l.setBorder(BorderFactory.createEmptyBorder(0, 18, 0, 12));
            l.setFont(l.getFont().deriveFont(isSelected ? Font.BOLD : Font.PLAIN, 13f));
            l.setOpaque(true);
            if (isSelected) {
                l.setBackground(UiTheme.SIDEBAR_SELECTED_BG);
                l.setForeground(UiTheme.SIDEBAR_TEXT_SELECTED);
            } else {
                l.setBackground(UiTheme.SIDEBAR_BG);
                l.setForeground(UiTheme.SIDEBAR_TEXT);
            }
            return l;
        }
    }

    public static void main(String[] args) {
        // Java 8 Swing: use the system look and feel so it doesn't look like Metal-era Java
        try {
            UIManager.setLookAndFeel(UIManager.getSystemLookAndFeelClassName());
        } catch (Exception ignored) {
        }
        SwingUtilities.invokeLater(() -> new BuildEngineUI().setVisible(true));
    }
}
