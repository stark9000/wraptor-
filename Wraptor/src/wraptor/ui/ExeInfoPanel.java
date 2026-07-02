package wraptor.ui;

 
import javax.swing.*;
import java.awt.*;
import wraptor.model.ProjectConfig;

public class ExeInfoPanel extends JPanel {

    public ExeInfoPanel(ProjectConfig config) {
        setLayout(new BorderLayout());
        setBackground(UiTheme.APP_BG);

        JPanel form = new JPanel(new GridBagLayout());
        GridBagConstraints c = new GridBagConstraints();
        c.fill = GridBagConstraints.HORIZONTAL;
        c.weightx = 1.0;
        c.insets = new Insets(4, 4, 4, 4);
        c.gridx = 0;
        int row = 0;

        ApplicationPanel.addSectionTitle(form, c, row,
                "EXE Info", "Version and company info shown in Windows Explorer \u2192 Properties \u2192 Details.");
        row += 3;

        c.gridy = row++;
        form.add(ApplicationPanel.sectionSubtitle("Version numbers"), c);

        c.gridy = row++;
        JPanel versionRow = new JPanel(new GridLayout(1, 2, 16, 0));
        versionRow.setOpaque(false);
        JPanel fv = new JPanel(new BorderLayout());
        fv.setOpaque(false);
        fv.add(ApplicationPanel.label("FILE VERSION (X.X.X.X)"), BorderLayout.NORTH);
        fv.add(ApplicationPanel.bindText(config.fileVersion, v -> config.fileVersion = v), BorderLayout.CENTER);
        JPanel pv = new JPanel(new BorderLayout());
        pv.setOpaque(false);
        pv.add(ApplicationPanel.label("PRODUCT VERSION (X.X.X.X)"), BorderLayout.NORTH);
        pv.add(ApplicationPanel.bindText(config.productVersion, v -> config.productVersion = v), BorderLayout.CENTER);
        versionRow.add(fv);
        versionRow.add(pv);
        form.add(versionRow, c);

        c.gridy = row++;
        form.add(ApplicationPanel.sectionSubtitle("Company & description"), c);

        c.gridy = row++;
        form.add(ApplicationPanel.label("COMPANY NAME"), c);
        c.gridy = row++;
        form.add(ApplicationPanel.bindText(config.companyName, v -> config.companyName = v), c);

        c.gridy = row++;
        form.add(ApplicationPanel.label("FILE DESCRIPTION"), c);
        c.gridy = row++;
        form.add(ApplicationPanel.bindText(config.fileDescription, v -> config.fileDescription = v), c);

        c.gridy = row++;
        form.add(ApplicationPanel.label("COPYRIGHT"), c);
        c.gridy = row++;
        form.add(ApplicationPanel.bindText(config.copyright, v -> config.copyright = v), c);

        c.gridy = row++;
        JLabel hint = new JLabel("<html>These appear in Windows Explorer \u2192 right-click \u2192 Properties \u2192 "
                + "Details tab.</html>");
        hint.setForeground(UiTheme.TEXT_MUTED);
        hint.setBorder(BorderFactory.createEmptyBorder(8, 0, 0, 0));
        form.add(hint, c);

        add(UiTheme.card(form), BorderLayout.CENTER);
    }
}
