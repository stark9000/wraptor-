package wraptor.ui;

import javax.swing.*;
import java.awt.*;

/**
 * Shared colors, fonts and small layout helpers so every panel looks like
 * part of the same app instead of raw default-Swing components scattered
 * across a big gray canvas.
 *
 * The core trick used throughout the UI: form content lives in a fixed-width
 * "card" (white panel, subtle border, generous padding) that sits inside a
 * light gray backdrop. Rows within the card use GridBagConstraints with
 * weightx = 1 so fields fill the card's width - but the card itself never
 * stretches past {@link #CARD_MAX_WIDTH}, so widening the window just adds
 * quiet margin around the card instead of stretching every checkbox row
 * into a mostly-empty strip.
 */
public final class UiTheme {

    public static final Color SIDEBAR_BG = new Color(0x24, 0x2A, 0x37);
    public static final Color SIDEBAR_TEXT = new Color(0xC7, 0xCE, 0xDA);
    public static final Color SIDEBAR_TEXT_SELECTED = Color.WHITE;
    public static final Color SIDEBAR_SELECTED_BG = new Color(0x2F, 0x6F, 0xED);

    public static final Color APP_BG = new Color(0xF0, 0xF1, 0xF5);
    public static final Color CARD_BG = Color.WHITE;
    public static final Color CARD_BORDER = new Color(0xDD, 0xE0, 0xE6);

    public static final Color ACCENT = new Color(0x2F, 0x6F, 0xED);
    public static final Color ACCENT_HOVER = new Color(0x27, 0x5F, 0xD1);
    public static final Color TEXT_PRIMARY = new Color(0x1E, 0x23, 0x2B);
    public static final Color TEXT_MUTED = new Color(0x74, 0x7B, 0x8A);
    public static final Color LABEL_MUTED = new Color(0x8C, 0x96, 0xA5);

    public static final Color SUCCESS = new Color(0x1E, 0x8E, 0x3E);
    public static final Color ERROR = new Color(0xD3, 0x2F, 0x2F);
    public static final Color LOG_MUTED = new Color(0x60, 0x66, 0x70);

    public static final int CARD_MAX_WIDTH = 560;

    private UiTheme() {
    }

    /**
     * Wraps a fully-populated form panel (GridBagLayout, rows already added)
     * in a fixed-width white card, left-aligned inside a light gray backdrop
     * that fills the rest of the available space. Call this once, last, in
     * each panel's constructor.
     *
     * The card's minimum size is pinned equal to its preferred size, so
     * GridBagLayout can never squash it smaller (which is what causes
     * FlowLayout rows - like icon/checkbox rows - to wrap and overlap the
     * row below instead of just... not fitting). If the window is ever
     * narrower than the card needs, the returned component scrolls instead
     * of corrupting itself.
     */
    public static JComponent card(JComponent form) {
        form.setBackground(CARD_BG);
        form.setBorder(BorderFactory.createCompoundBorder(
                BorderFactory.createLineBorder(CARD_BORDER, 1),
                BorderFactory.createEmptyBorder(22, 26, 22, 26)));

        Dimension pref = form.getPreferredSize();
        Dimension fixed = new Dimension(CARD_MAX_WIDTH, pref.height);
        form.setPreferredSize(fixed);
        form.setMinimumSize(fixed);
        form.setMaximumSize(new Dimension(CARD_MAX_WIDTH, Integer.MAX_VALUE));

        JPanel outer = new JPanel(new GridBagLayout());
        outer.setBackground(APP_BG);
        GridBagConstraints c = new GridBagConstraints();
        c.gridx = 0;
        c.gridy = 0;
        c.anchor = GridBagConstraints.NORTHWEST;
        c.insets = new Insets(20, 20, 20, 20);
        outer.add(form, c);

        // consume all remaining width/height as quiet margin, not a stretched field
        c.gridx = 1;
        c.weightx = 1;
        c.fill = GridBagConstraints.HORIZONTAL;
        outer.add(Box.createHorizontalGlue(), c);
        c.gridx = 0;
        c.gridy = 1;
        c.gridwidth = 2;
        c.weighty = 1;
        c.fill = GridBagConstraints.VERTICAL;
        outer.add(Box.createVerticalGlue(), c);

        JScrollPane scroll = new JScrollPane(outer);
        scroll.setBorder(BorderFactory.createEmptyBorder());
        scroll.getViewport().setBackground(APP_BG);
        scroll.getVerticalScrollBar().setUnitIncrement(16);
        return scroll;
    }

    public static JButton primaryButton(String text) {
        JButton b = new JButton(text) {
            @Override
            protected void paintComponent(Graphics g) {
                Graphics2D g2 = (Graphics2D) g.create();
                g2.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
                g2.setColor(getModel().isRollover() || getModel().isPressed() ? ACCENT_HOVER : ACCENT);
                g2.fillRoundRect(0, 0, getWidth(), getHeight(), 6, 6);
                g2.dispose();
                super.paintComponent(g);
            }
        };
        b.setForeground(Color.WHITE);
        b.setFont(b.getFont().deriveFont(Font.BOLD, 13f));
        b.setBorder(BorderFactory.createEmptyBorder(9, 22, 9, 22));
        b.setFocusPainted(false);
        b.setContentAreaFilled(false);
        b.setOpaque(false);
        b.setCursor(Cursor.getPredefinedCursor(Cursor.HAND_CURSOR));
        return b;
    }

    /** Lighter, outlined button for secondary actions that sit next to a primaryButton. */
    public static JButton secondaryButton(String text) {
        JButton b = new JButton(text) {
            @Override
            protected void paintComponent(Graphics g) {
                Graphics2D g2 = (Graphics2D) g.create();
                g2.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
                g2.setColor(CARD_BG);
                g2.fillRoundRect(0, 0, getWidth() - 1, getHeight() - 1, 6, 6);
                g2.setColor(getModel().isRollover() || getModel().isPressed() ? ACCENT : CARD_BORDER);
                g2.drawRoundRect(0, 0, getWidth() - 1, getHeight() - 1, 6, 6);
                g2.dispose();
                super.paintComponent(g);
            }
        };
        b.setForeground(ACCENT);
        b.setFont(b.getFont().deriveFont(Font.BOLD, 12f));
        b.setBorder(BorderFactory.createEmptyBorder(7, 16, 7, 16));
        b.setFocusPainted(false);
        b.setContentAreaFilled(false);
        b.setOpaque(false);
        b.setCursor(Cursor.getPredefinedCursor(Cursor.HAND_CURSOR));
        return b;
    }

    /** Same outlined style as secondaryButton, but in red - for destructive actions (remove/clear). */
    public static JButton dangerButton(String text) {
        JButton b = new JButton(text) {
            @Override
            protected void paintComponent(Graphics g) {
                Graphics2D g2 = (Graphics2D) g.create();
                g2.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
                g2.setColor(CARD_BG);
                g2.fillRoundRect(0, 0, getWidth() - 1, getHeight() - 1, 6, 6);
                g2.setColor(getModel().isRollover() || getModel().isPressed() ? UiTheme.ERROR : CARD_BORDER);
                g2.drawRoundRect(0, 0, getWidth() - 1, getHeight() - 1, 6, 6);
                g2.dispose();
                super.paintComponent(g);
            }
        };
        b.setForeground(ERROR);
        b.setFont(b.getFont().deriveFont(Font.BOLD, 12f));
        b.setBorder(BorderFactory.createEmptyBorder(7, 16, 7, 16));
        b.setFocusPainted(false);
        b.setContentAreaFilled(false);
        b.setOpaque(false);
        b.setCursor(Cursor.getPredefinedCursor(Cursor.HAND_CURSOR));
        return b;
    }

    /**
     * A checkbox with a custom-painted indicator instead of the L&F's native
     * one. Necessary, not cosmetic: on Windows L&F, the checkbox glyph is
     * drawn by the native theme engine and ignores setBackground/setOpaque
     * entirely, so it shows up as a gray Windows-style box even sitting on
     * this UI's white card - there's no combination of Swing color calls
     * that fixes that. Painting the indicator ourselves is the only reliable
     * fix, and it also means the checkbox now matches the app's own accent
     * color when checked instead of the OS theme's.
     */
    public static JCheckBox checkbox(String text, boolean initial) {
        JCheckBox cb = new JCheckBox(text, initial);
        cb.setOpaque(false);
        cb.setFocusPainted(false);
        cb.setCursor(Cursor.getPredefinedCursor(Cursor.HAND_CURSOR));
        cb.setIcon(new CheckboxIcon(cb, false));
        cb.setSelectedIcon(new CheckboxIcon(cb, true));
        return cb;
    }

    private static class CheckboxIcon implements Icon {
        private static final int SIZE = 15;
        private final JCheckBox owner;
        private final boolean selected;

        CheckboxIcon(JCheckBox owner, boolean selected) {
            this.owner = owner;
            this.selected = selected;
        }

        @Override
        public void paintIcon(Component c, Graphics g, int x, int y) {
            Graphics2D g2 = (Graphics2D) g.create();
            g2.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
            g2.setColor(selected ? ACCENT : CARD_BG);
            g2.fillRoundRect(x, y, SIZE, SIZE, 4, 4);
            g2.setColor(selected ? ACCENT : (owner.getModel().isRollover() ? ACCENT : CARD_BORDER));
            g2.drawRoundRect(x, y, SIZE - 1, SIZE - 1, 4, 4);
            if (selected) {
                g2.setColor(Color.WHITE);
                g2.setStroke(new BasicStroke(2f, BasicStroke.CAP_ROUND, BasicStroke.JOIN_ROUND));
                g2.drawLine(x + 3, y + 8, x + 6, y + 11);
                g2.drawLine(x + 6, y + 11, x + 12, y + 4);
            }
            g2.dispose();
        }

        @Override
        public int getIconWidth() {
            return SIZE;
        }

        @Override
        public int getIconHeight() {
            return SIZE;
        }
    }
}
