package studyplanner.ui;

import javax.swing.*;
import javax.swing.border.EmptyBorder;
import javax.swing.table.DefaultTableCellRenderer;
import javax.swing.table.DefaultTableModel;
import javax.swing.table.TableCellRenderer;
import java.awt.*;
import java.awt.event.ActionEvent;
import java.awt.event.ComponentAdapter;
import java.awt.event.ComponentEvent;
import java.awt.event.FocusAdapter;
import java.awt.event.FocusEvent;
import java.awt.event.MouseAdapter;
import java.awt.event.MouseEvent;
import java.awt.event.MouseMotionAdapter;
import java.awt.event.WindowAdapter;
import java.awt.event.WindowEvent;
import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * The app's look in one place: colours, fonts, cards, buttons, coloured tags, icons, tables and dialogs.
 * Only plain Swing and Java 2D are used (no image files, no third-party libraries).
 */
final class Ui {
    private Ui() {
    }

    // ------------------------------------------------------------------ colours, fonts and formats

    static final String FONT = pickFont();
    static final Color NAVY = new Color(14, 21, 44), NAVY_2 = new Color(27, 38, 76), NAVY_3 = new Color(45, 62, 114);
    static final Color NAV_TEXT = new Color(178, 191, 220), NAV_MUTED = new Color(112, 129, 170);
    static final Color BLUE = new Color(59, 110, 230), BLUE_SOFT = new Color(232, 240, 255), BLUE_INK = new Color(36, 68, 150);
    static final Color GREEN = new Color(16, 148, 90), GREEN_SOFT = new Color(221, 246, 233);
    static final Color AMBER = new Color(176, 112, 0), AMBER_SOFT = new Color(255, 244, 199);
    static final Color ORANGE = new Color(204, 88, 18), ORANGE_SOFT = new Color(255, 236, 220);
    static final Color RED = new Color(208, 46, 62), RED_SOFT = new Color(254, 229, 232);
    static final Color VIOLET = new Color(118, 78, 220), VIOLET_SOFT = new Color(239, 233, 255);
    static final Color BG = new Color(243, 246, 251), LINE = new Color(228, 233, 242), ROW_LINE = new Color(238, 241, 247);
    static final Color INK = new Color(20, 28, 50), MUTED = new Color(104, 115, 140), HOVER = new Color(246, 248, 253);
    static final Font F_SMALL = new Font(FONT, Font.PLAIN, 12), F_TEXT = new Font(FONT, Font.PLAIN, 13);
    static final Font F_BOLD = new Font(FONT, Font.BOLD, 13), F_CAPS = caps(11);
    static final Cursor HAND = Cursor.getPredefinedCursor(Cursor.HAND_CURSOR);
    static final DateTimeFormatter DAY = DateTimeFormatter.ofPattern("EEE d MMM yyyy", Locale.ENGLISH);
    static final DateTimeFormatter SHORT_DAY = DateTimeFormatter.ofPattern("EEE d MMM", Locale.ENGLISH);
    static final DateTimeFormatter LONG_DAY = DateTimeFormatter.ofPattern("EEEE, d MMMM yyyy", Locale.ENGLISH);

    /** Applies the app font and calmer defaults to the standard Swing parts (dialogs, lists, tooltips). */
    static void installTheme() {
        Font base = new Font(FONT, Font.PLAIN, 13);
        for (Object key : Collections.list(UIManager.getDefaults().keys())) {
            if (UIManager.get(key) instanceof javax.swing.plaf.FontUIResource) {
                UIManager.put(key, new javax.swing.plaf.FontUIResource(base));
            }
        }
        UIManager.put("Panel.background", Color.WHITE);
        UIManager.put("OptionPane.background", Color.WHITE);
        UIManager.put("OptionPane.messageForeground", INK);
        UIManager.put("OptionPane.buttonOrientation", SwingConstants.RIGHT);
        UIManager.put("ComboBox.background", Color.WHITE);
        UIManager.put("ComboBox.selectionBackground", BLUE_SOFT);
        UIManager.put("ComboBox.selectionForeground", INK);
        UIManager.put("ToolTip.background", NAVY);
        UIManager.put("ToolTip.foreground", Color.WHITE);
        UIManager.put("ToolTip.border", BorderFactory.createEmptyBorder(6, 10, 6, 10));
        UIManager.put("PopupMenu.border", BorderFactory.createLineBorder(LINE));
    }

    /** The first good-looking font that is installed (Segoe UI on Windows, Helvetica Neue on macOS). */
    private static String pickFont() {
        List<String> installed = Arrays.asList(GraphicsEnvironment.getLocalGraphicsEnvironment().getAvailableFontFamilyNames());
        for (String f : new String[]{"Segoe UI", "Helvetica Neue", "Inter", "Noto Sans", "DejaVu Sans"}) {
            if (installed.contains(f)) {
                return f;
            }
        }
        return Font.SANS_SERIF;
    }

    /** Bold small text with a little letter spacing, for labels such as "REMAINING STUDY HOURS". */
    static Font caps(int size) {
        Map<java.awt.font.TextAttribute, Object> a = new HashMap<>();
        a.put(java.awt.font.TextAttribute.TRACKING, 0.06);
        return new Font(FONT, Font.BOLD, size).deriveFont(a);
    }

    /** Turns on anti-aliasing (and the desktop's text settings) for custom painting. */
    static void smooth(Graphics2D g) {
        g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
        Object hints = Toolkit.getDefaultToolkit().getDesktopProperty("awt.font.desktophints");
        if (hints instanceof Map) {
            g.addRenderingHints((Map<?, ?>) hints);
        } else {
            g.setRenderingHint(RenderingHints.KEY_TEXT_ANTIALIASING, RenderingHints.VALUE_TEXT_ANTIALIAS_ON);
        }
    }

    /** Hours with one decimal, for example "1.5 hrs". */
    static String hours(double h) {
        return String.format(Locale.ENGLISH, "%.1f hrs", h);
    }

    static String plural(int n, String one, String many) {
        return n + " " + (n == 1 ? one : many);
    }

    /** Days from today to the given date (negative when it is in the past). */
    static long days(LocalDate d) {
        return d.toEpochDay() - LocalDate.now().toEpochDay();
    }

    // ------------------------------------------------------------------ labels, panels and cards

    static JLabel label(String text, int style, int size, Color color) {
        JLabel l = new JLabel(text);
        l.setFont(new Font(FONT, style, size));
        l.setForeground(color);
        return l;
    }

    /** A muted note, for example "3 warnings", with a little room so it is never clipped at a card edge. */
    static JLabel note() {
        JLabel l = label(" ", Font.PLAIN, 12, MUTED);
        l.setBorder(new EmptyBorder(0, 8, 0, 4));
        return l;
    }

    /** Makes a component span the full width of a vertical BoxLayout and line up on the left. */
    static <T extends JComponent> T fullWidth(T c) {
        c.setAlignmentX(Component.LEFT_ALIGNMENT);
        c.setMaximumSize(new Dimension(Integer.MAX_VALUE, c.getPreferredSize().height));
        return c;
    }

    static JPanel page() {
        JPanel p = new JPanel(new BorderLayout(16, 18));
        p.setOpaque(false);
        p.setBorder(new EmptyBorder(22, 28, 24, 28));
        return p;
    }

    /** Page title with a muted line under it, and optional buttons on the right. */
    static JPanel pageHead(String title, JComponent subtitle, JComponent... right) {
        JPanel text = new JPanel();
        text.setLayout(new BoxLayout(text, BoxLayout.Y_AXIS));
        text.setOpaque(false);
        text.add(fullWidth(label(title, Font.BOLD, 22, INK)));
        text.add(Box.createVerticalStrut(3));
        text.add(fullWidth(subtitle));
        JPanel buttons = new JPanel(new FlowLayout(FlowLayout.RIGHT, 10, 0));
        buttons.setOpaque(false);
        for (JComponent c : right) {
            buttons.add(c);
        }
        JPanel head = new JPanel(new BorderLayout(16, 0));
        head.setOpaque(false);
        head.add(text, BorderLayout.CENTER);
        JPanel east = new JPanel(new GridBagLayout());
        east.setOpaque(false);
        east.add(buttons);
        head.add(east, BorderLayout.EAST);
        return head;
    }

    /** A white rounded card with a soft shadow. */
    static JPanel card(LayoutManager lm) {
        JPanel p = new JPanel(lm) {
            protected void paintComponent(Graphics g) {
                Graphics2D g2 = (Graphics2D) g.create();
                smooth(g2);
                int w = getWidth() - 1, h = getHeight() - 5;
                g2.setColor(new Color(20, 32, 70, 10));
                g2.fillRoundRect(1, 4, w - 1, h, 18, 18);
                g2.setColor(new Color(20, 32, 70, 8));
                g2.fillRoundRect(0, 2, w, h, 18, 18);
                g2.setColor(Color.WHITE);
                g2.fillRoundRect(0, 0, w, h, 18, 18);
                g2.setColor(LINE);
                g2.drawRoundRect(0, 0, w, h, 18, 18);
                g2.dispose();
            }
        };
        p.setOpaque(false);
        p.setBorder(new EmptyBorder(18, 20, 22, 20));
        return p;
    }

    /** A card with a heading (and an optional element at the right of the heading) above its body. */
    static JPanel section(String heading, JComponent aside, Component body) {
        return section(label(heading, Font.BOLD, 15, INK), aside, body);
    }

    static JPanel section(JComponent heading, JComponent aside, Component body) {
        JPanel top = new JPanel(new BorderLayout(12, 0));
        top.setOpaque(false);
        top.add(heading, BorderLayout.CENTER);
        if (aside != null) {
            top.add(aside, BorderLayout.EAST);
        }
        JPanel c = card(new BorderLayout(0, 10));
        c.add(top, BorderLayout.NORTH);
        c.add(body, BorderLayout.CENTER);
        return c;
    }

    /** An empty vertical list; rows are added later. */
    static JPanel stack() {
        JPanel l = new JPanel();
        l.setLayout(new BoxLayout(l, BoxLayout.Y_AXIS));
        l.setOpaque(false);
        return l;
    }

    /** A borderless scroll pane with a thin, rounded scroll bar. */
    static JScrollPane scroll(final Component view) {
        JScrollPane sp = new JScrollPane(view);
        sp.setBorder(BorderFactory.createEmptyBorder());
        sp.setViewportBorder(null);
        sp.setOpaque(false);
        sp.getViewport().setBackground(Color.WHITE);
        for (JScrollBar b : new JScrollBar[]{sp.getVerticalScrollBar(), sp.getHorizontalScrollBar()}) {
            b.setUI(thinScrollBar());
            b.setOpaque(false);
            b.setUnitIncrement(18);
        }
        sp.getVerticalScrollBar().setPreferredSize(new Dimension(10, 0));
        sp.getHorizontalScrollBar().setPreferredSize(new Dimension(0, 10));
        sp.getViewport().addComponentListener(new ComponentAdapter() {
            public void componentResized(ComponentEvent e) {
                SwingUtilities.invokeLater(view::revalidate);   // lets wrapped text measure the new width
            }
        });
        return sp;
    }

    /** A scroll pane for a vertical list: the list keeps its natural height and never scrolls sideways. */
    static JScrollPane scrollList(JPanel list) {
        JPanel holder = new JPanel(new BorderLayout());
        holder.setOpaque(false);
        holder.add(list, BorderLayout.NORTH);
        JScrollPane sp = scroll(holder);
        sp.setHorizontalScrollBarPolicy(JScrollPane.HORIZONTAL_SCROLLBAR_NEVER);
        sp.getViewport().setOpaque(false);
        return sp;
    }

    private static javax.swing.plaf.basic.BasicScrollBarUI thinScrollBar() {
        return new javax.swing.plaf.basic.BasicScrollBarUI() {
            protected void configureScrollBarColors() {
                thumbColor = new Color(203, 210, 224);
            }

            protected JButton createDecreaseButton(int orientation) {
                return noButton();
            }

            protected JButton createIncreaseButton(int orientation) {
                return noButton();
            }

            private JButton noButton() {
                JButton b = new JButton();
                Dimension zero = new Dimension(0, 0);
                b.setPreferredSize(zero);
                b.setMinimumSize(zero);
                b.setMaximumSize(zero);
                return b;
            }

            protected void paintTrack(Graphics g, JComponent c, Rectangle r) {
            }

            protected void paintThumb(Graphics g, JComponent c, Rectangle r) {
                if (r.isEmpty() || !scrollbar.isEnabled()) {
                    return;
                }
                Graphics2D g2 = (Graphics2D) g.create();
                smooth(g2);
                g2.setColor(isThumbRollover() || isDragging ? new Color(160, 170, 190) : thumbColor);
                g2.fillRoundRect(r.x + 2, r.y + 2, r.width - 4, r.height - 4, 6, 6);
                g2.dispose();
            }
        };
    }

    /** One list entry: something on the left (a tag or an icon) and a title with a muted detail line. */
    static JPanel listRow(JComponent lead, int leadWidth, String title, String detail) {
        JPanel left = new JPanel(new FlowLayout(FlowLayout.LEFT, 0, 1));
        left.setOpaque(false);
        left.add(lead);
        left.setPreferredSize(new Dimension(leadWidth, lead.getPreferredSize().height + 2));
        JPanel text = new JPanel();
        text.setLayout(new BoxLayout(text, BoxLayout.Y_AXIS));
        text.setOpaque(false);
        text.add(wrapText(title, F_BOLD, INK));
        if (detail != null && !detail.isEmpty()) {
            text.add(Box.createVerticalStrut(3));
            text.add(wrapText(detail, F_SMALL, MUTED));
        }
        JPanel row = new JPanel(new BorderLayout(12, 0));
        row.setOpaque(false);
        row.setBorder(BorderFactory.createCompoundBorder(
                BorderFactory.createMatteBorder(0, 0, 1, 0, ROW_LINE), new EmptyBorder(9, 0, 9, 6)));
        row.add(left, BorderLayout.WEST);
        row.add(text, BorderLayout.CENTER);
        return row;
    }

    static JPanel emptyRow(String iconKind, Color fg, Color bg, String title, String detail) {
        JPanel row = listRow(new JLabel(badge(iconKind, 36, fg, bg)), 48, title, detail);
        row.setBorder(new EmptyBorder(8, 0, 8, 6));
        return row;
    }

    /** Read-only text that wraps to the width it is given (a JLabel cannot wrap). */
    static JTextArea wrapText(String text, Font font, Color color) {
        JTextArea a = new JTextArea(text) {
            public Dimension getPreferredSize() {   // never ask for more width than the layout gives
                return new Dimension(40, super.getPreferredSize().height);
            }
        };
        a.setCaret(new javax.swing.text.DefaultCaret() {
            protected void adjustVisibility(Rectangle r) {
                // never scroll the surrounding list to this text's cursor
            }
        });
        a.setLineWrap(true);
        a.setWrapStyleWord(true);
        a.setEditable(false);
        a.setFocusable(false);
        a.setOpaque(false);
        a.setHighlighter(null);
        a.setBorder(null);
        a.setFont(font);
        a.setForeground(color);
        a.setCursor(Cursor.getDefaultCursor());
        a.setSize(new Dimension(300, 1));   // a first guess of the width until the real layout runs
        return a;
    }

    /** Lays out again now, and once more after wrapped text has measured its real width. */
    static void relayout(final JComponent... cs) {
        for (JComponent c : cs) {
            c.revalidate();
            c.repaint();
        }
        SwingUtilities.invokeLater(() -> {
            for (JComponent c : cs) {
                c.revalidate();
                c.repaint();
            }
        });
    }

    /** A thin rounded progress bar; the fraction (0..1) is read from the "value" client property. */
    static JComponent bar(final Color color) {
        JComponent b = new JComponent() {
            protected void paintComponent(Graphics g) {
                Object v = getClientProperty("value");
                double f = v instanceof Double ? Math.max(0, Math.min(1, (Double) v)) : 0;
                Graphics2D g2 = (Graphics2D) g.create();
                smooth(g2);
                int h = getHeight();
                g2.setColor(new Color(233, 237, 245));
                g2.fillRoundRect(0, 0, getWidth(), h, h, h);
                if (f > 0) {
                    Object c = getClientProperty("color");
                    g2.setColor(c instanceof Color ? (Color) c : color);
                    g2.fillRoundRect(0, 0, Math.max(h, (int) Math.round(getWidth() * f)), h, h, h);
                }
                g2.dispose();
            }
        };
        b.setPreferredSize(new Dimension(80, 6));
        return b;
    }

    // ------------------------------------------------------------------ buttons, tags and fields

    /** Common settings for buttons that paint their own background. */
    static void flat(AbstractButton b) {
        b.setContentAreaFilled(false);
        b.setBorderPainted(false);
        b.setFocusPainted(false);
        b.setOpaque(false);
        b.setRolloverEnabled(true);
        b.setCursor(HAND);
    }

    /** A rounded button. kind: "primary" (blue), "success" (green), "danger" (red), "soft-danger" or "plain". */
    static JButton button(String text, String iconKind, String kind) {
        final Color bg, fg, edge;
        switch (kind) {
            case "primary": bg = BLUE; fg = Color.WHITE; edge = null; break;
            case "success": bg = GREEN; fg = Color.WHITE; edge = null; break;
            case "danger": bg = RED; fg = Color.WHITE; edge = null; break;
            case "soft-danger": bg = RED_SOFT; fg = RED; edge = null; break;
            default: bg = Color.WHITE; fg = INK; edge = new Color(212, 219, 231); break;
        }
        JButton b = new JButton(text) {
            protected void paintComponent(Graphics g) {
                Graphics2D g2 = (Graphics2D) g.create();
                smooth(g2);
                ButtonModel m = getModel();
                Color fill = !isEnabled() ? new Color(238, 241, 246) : m.isPressed() ? shade(bg, 0.86f) : m.isRollover() ? shade(bg, 0.94f) : bg;
                g2.setColor(fill);
                g2.fillRoundRect(0, 0, getWidth() - 1, getHeight() - 1, 12, 12);
                if (edge != null && isEnabled()) {
                    g2.setColor(edge);
                    g2.drawRoundRect(0, 0, getWidth() - 1, getHeight() - 1, 12, 12);
                }
                if (isFocusOwner()) {   // keyboard focus ring
                    g2.setColor(new Color(59, 110, 230, 120));
                    g2.setStroke(new BasicStroke(2f));
                    g2.drawRoundRect(1, 1, getWidth() - 3, getHeight() - 3, 11, 11);
                }
                g2.dispose();
                super.paintComponent(g);
            }
        };
        if (iconKind != null) {
            b.setIcon(icon(iconKind, 16, fg));
            b.setDisabledIcon(icon(iconKind, 16, new Color(170, 178, 195)));
            b.setIconTextGap(8);
        }
        b.setForeground(fg);
        b.setFont(F_BOLD);
        flat(b);
        b.setBorder(new EmptyBorder(9, iconKind == null ? 18 : 14, 9, 18));
        b.addFocusListener(new FocusAdapter() {
            public void focusGained(FocusEvent e) {
                e.getComponent().repaint();
            }

            public void focusLost(FocusEvent e) {
                e.getComponent().repaint();
            }
        });
        return b;
    }

    static Color shade(Color c, float f) {
        return new Color(Math.round(c.getRed() * f), Math.round(c.getGreen() * f), Math.round(c.getBlue() * f));
    }

    /** A label on a rounded coloured background (date chips, hints, toasts). */
    static JLabel pill(String text, Icon icon, final Color bg, Color fg) {
        JLabel l = new JLabel(text, icon, SwingConstants.LEFT) {
            protected void paintComponent(Graphics g) {
                Graphics2D g2 = (Graphics2D) g.create();
                smooth(g2);
                Object r = getClientProperty("radius");
                int radius = r instanceof Integer ? (Integer) r : getHeight();
                g2.setColor(bg);
                g2.fillRoundRect(0, 0, getWidth(), getHeight(), radius, radius);
                g2.dispose();
                super.paintComponent(g);
            }
        };
        l.setForeground(fg);
        l.setFont(F_BOLD);
        return l;
    }

    /** A small coloured tag such as "OVERDUE". */
    static JLabel chip(String text, Color bg, Color fg) {
        JLabel l = pill(text, null, bg, fg);
        l.setFont(caps(10));
        l.setBorder(new EmptyBorder(4, 9, 4, 9));
        return l;
    }

    /** A text field with a light placeholder that is shown while it is empty. */
    static JTextField field(String text, final String placeholder) {
        JTextField f = new JTextField(text) {
            protected void paintComponent(Graphics g) {
                super.paintComponent(g);
                if (getText().isEmpty() && placeholder != null) {
                    Graphics2D g2 = (Graphics2D) g.create();
                    smooth(g2);
                    g2.setColor(new Color(160, 168, 186));
                    g2.setFont(getFont());
                    FontMetrics fm = g2.getFontMetrics();
                    Insets in = getInsets();
                    g2.drawString(placeholder, in.left, in.top + (getHeight() - in.top - in.bottom - fm.getHeight()) / 2 + fm.getAscent());
                    g2.dispose();
                }
            }
        };
        styleField(f);
        return f;
    }

    /** A drop-down list in the app's style. */
    static <T> JComboBox<T> combo(T[] values) {
        JComboBox<T> c = new JComboBox<>(values);
        styleField(c);
        return c;
    }

    /** Rounded border for form fields; it turns blue while the field has the keyboard focus. */
    static javax.swing.border.Border fieldBorder(final Insets pad) {
        return new javax.swing.border.AbstractBorder() {
            public void paintBorder(Component c, Graphics g, int x, int y, int w, int h) {
                Graphics2D g2 = (Graphics2D) g.create();
                smooth(g2);
                if (c.isFocusOwner()) {
                    g2.setColor(new Color(59, 110, 230, 45));
                    g2.setStroke(new BasicStroke(4f));
                    g2.drawRoundRect(x + 2, y + 2, w - 5, h - 5, 10, 10);
                    g2.setColor(BLUE);
                    g2.setStroke(new BasicStroke(1.4f));
                } else {
                    g2.setColor(new Color(208, 215, 228));
                }
                g2.drawRoundRect(x + 1, y + 1, w - 3, h - 3, 10, 10);
                g2.dispose();
            }

            public Insets getBorderInsets(Component c) {
                return new Insets(pad.top, pad.left, pad.bottom, pad.right);
            }

            public Insets getBorderInsets(Component c, Insets i) {
                i.set(pad.top, pad.left, pad.bottom, pad.right);
                return i;
            }
        };
    }

    /** Gives text fields and drop-downs the same rounded look and a blue focus border. */
    static void styleField(final JComponent f) {
        f.setFont(F_TEXT);
        f.setForeground(INK);
        if (f instanceof JTextField) {
            JTextField t = (JTextField) f;
            t.setBorder(fieldBorder(new Insets(8, 11, 8, 11)));
            t.setOpaque(false);
            t.setCaretColor(BLUE);
            t.setSelectionColor(new Color(200, 218, 255));
        } else if (f instanceof JComboBox) {
            JComboBox<?> cb = (JComboBox<?>) f;
            cb.setUI(new javax.swing.plaf.basic.BasicComboBoxUI() {
                protected JButton createArrowButton() {
                    JButton b = new JButton(icon("chevron", 14, MUTED));
                    flat(b);
                    b.setBorder(new EmptyBorder(0, 6, 0, 10));
                    return b;
                }

                public void paintCurrentValueBackground(Graphics g, Rectangle bounds, boolean hasFocus) {
                    // the white background shows through; focus is shown by the blue border
                }

                public void paintCurrentValue(Graphics g, Rectangle bounds, boolean hasFocus) {
                    super.paintCurrentValue(g, bounds, false);
                }
            });
            cb.setRenderer(new DefaultListCellRenderer() {
                public Component getListCellRendererComponent(JList<?> list, Object value, int index, boolean sel, boolean focus) {
                    super.getListCellRendererComponent(list, value, index, sel, false);
                    setBorder(new EmptyBorder(6, 9, 6, 9));
                    return this;
                }
            });
            cb.setBackground(Color.WHITE);
            cb.setOpaque(false);
            cb.setBorder(fieldBorder(new Insets(2, 2, 2, 2)));
            cb.setCursor(HAND);
        }
        f.addFocusListener(new FocusAdapter() {
            public void focusGained(FocusEvent e) {
                f.repaint();
            }

            public void focusLost(FocusEvent e) {
                f.repaint();
            }
        });
    }

    /** A small label above a field. */
    static JComponent labelled(String text, JComponent field) {
        JPanel p = new JPanel(new BorderLayout(0, 5));
        p.setOpaque(false);
        p.add(label(text, Font.BOLD, 12, INK), BorderLayout.NORTH);
        p.add(field, BorderLayout.CENTER);
        return p;
    }

    // ------------------------------------------------------------------ tables

    /** A table model whose cells cannot be typed into. */
    static DefaultTableModel readOnlyModel(String... columns) {
        return new DefaultTableModel(columns, 0) {
            public boolean isCellEditable(int r, int c) {
                return false;
            }
        };
    }

    /** A clean table: no grid, roomy rows, hover highlight, styled header and a friendly message when empty. */
    static JTable table(DefaultTableModel m, final String iconKind, final String emptyText) {
        final JTable t = new JTable(m) {
            /** Fill the width when there is room; otherwise keep readable columns and scroll sideways. */
            public boolean getScrollableTracksViewportWidth() {
                return getParent() == null || wantedWidth() <= getParent().getWidth();
            }

            public Dimension getPreferredSize() {
                Dimension d = super.getPreferredSize();
                d.width = wantedWidth();
                return d;
            }

            /** The sum of the column widths chosen for readability (see widths()). */
            private int wantedWidth() {
                int sum = 0;
                for (int i = 0; i < getColumnModel().getColumnCount(); i++) {
                    sum += getColumnModel().getColumn(i).getPreferredWidth();
                }
                return sum;
            }

            protected void paintComponent(Graphics g) {
                super.paintComponent(g);
                if (getRowCount() == 0) {
                    Graphics2D g2 = (Graphics2D) g.create();
                    smooth(g2);
                    Rectangle r = getVisibleRect();
                    int cy = r.y + r.height / 2 - 34;
                    badge(iconKind, 52, BLUE, BLUE_SOFT).paintIcon(this, g2, r.x + (r.width - 52) / 2, cy - 26);
                    g2.setFont(new Font(FONT, Font.BOLD, 15));
                    g2.setColor(INK);
                    centre(g2, "Nothing here yet", r, cy + 50);
                    g2.setFont(F_TEXT);
                    g2.setColor(MUTED);
                    centre(g2, emptyText, r, cy + 72);
                    g2.dispose();
                }
            }
        };
        t.setRowHeight(42);
        t.setShowGrid(false);
        t.setIntercellSpacing(new Dimension(0, 0));
        t.setFillsViewportHeight(true);
        t.setBackground(Color.WHITE);
        t.setSelectionBackground(new Color(226, 236, 255));
        t.setSelectionForeground(INK);
        t.setSelectionMode(ListSelectionModel.SINGLE_SELECTION);
        t.setDefaultRenderer(Object.class, cells());
        t.setDefaultRenderer(Boolean.class, cells());
        t.setDefaultRenderer(Double.class, cells());
        t.getTableHeader().setReorderingAllowed(false);
        t.getTableHeader().setBackground(Color.WHITE);   // hides hairline seams at 125 % / 150 % display scaling
        t.getTableHeader().setDefaultRenderer(new TableCellRenderer() {
            private final JLabel l = new JLabel();

            {
                l.setOpaque(true);
                l.setBackground(Color.WHITE);
                l.setForeground(MUTED);
                l.setFont(F_CAPS);
                l.setBorder(BorderFactory.createCompoundBorder(
                        BorderFactory.createMatteBorder(0, 0, 1, 0, LINE), new EmptyBorder(11, 12, 10, 12)));
            }

            public Component getTableCellRendererComponent(JTable tb, Object v, boolean s, boolean f, int r, int c) {
                l.setText(String.valueOf(v).toUpperCase(Locale.ENGLISH));
                return l;
            }
        });
        t.addMouseMotionListener(new MouseMotionAdapter() {   // highlight the row under the mouse
            public void mouseMoved(MouseEvent e) {
                Integer row = t.rowAtPoint(e.getPoint());
                if (!row.equals(t.getClientProperty("hover"))) {
                    t.putClientProperty("hover", row);
                    t.repaint();
                }
            }
        });
        t.addMouseListener(new MouseAdapter() {
            public void mouseExited(MouseEvent e) {
                t.putClientProperty("hover", -1);
                t.repaint();
            }
        });
        return t;
    }

    /** The table inside a borderless scroll pane with a white header area. */
    static JScrollPane tableScroll(JTable t) {
        JScrollPane sp = scroll(t);
        sp.setColumnHeaderView(t.getTableHeader());
        sp.getColumnHeader().setBackground(Color.WHITE);
        JPanel corner = new JPanel();
        corner.setBackground(Color.WHITE);
        sp.setCorner(JScrollPane.UPPER_RIGHT_CORNER, corner);
        return sp;
    }

    static void centre(Graphics2D g, String s, Rectangle r, int baseline) {
        g.drawString(s, r.x + (r.width - g.getFontMetrics().stringWidth(s)) / 2, baseline);
    }

    static void widths(JTable t, int... w) {
        for (int i = 0; i < w.length; i++) {
            t.getColumnModel().getColumn(i).setPreferredWidth(w[i]);
        }
    }

    /** Binds a key (for example "DELETE") on a component to an action. */
    static void onKey(JComponent c, String key, final Runnable action) {
        c.getInputMap(JComponent.WHEN_FOCUSED).put(KeyStroke.getKeyStroke(key), "key:" + key);
        c.getActionMap().put("key:" + key, new AbstractAction() {
            public void actionPerformed(ActionEvent e) {
                action.run();
            }
        });
    }

    /** Index of a column by its header name, or -1. */
    private static int column(JTable t, String name) {
        for (int i = 0; i < t.getModel().getColumnCount(); i++) {
            if (t.getModel().getColumnName(i).equals(name)) {
                return i;
            }
        }
        return -1;
    }

    /**
     * Formats every table cell: padding, row lines, hover, readable dates and hours, coloured tags for type and
     * status, the Mark Complete checkbox and progress bars. The table models keep the raw values.
     */
    private static TableCellRenderer cells() {
        return new DefaultTableCellRenderer() {
            private final javax.swing.border.Border pad = BorderFactory.createCompoundBorder(
                    BorderFactory.createMatteBorder(0, 0, 1, 0, ROW_LINE), new EmptyBorder(0, 12, 0, 12));
            private String mode;              // null, "tag", "check", "progress" or "twoLine"
            private String detail;            // second line of a "twoLine" cell
            private Color tagBg, tagFg;
            private boolean checked, hovered;
            private double fraction;

            public Component getTableCellRendererComponent(JTable t, Object v, boolean sel, boolean focus, int row, int col) {
                String column = t.getColumnName(col);
                super.getTableCellRendererComponent(t, text(column, v), sel, false, row, col);
                hovered = Integer.valueOf(row).equals(t.getClientProperty("hover"));
                setBackground(sel ? t.getSelectionBackground() : hovered ? HOVER : Color.WHITE);
                setForeground(INK);
                setFont(F_TEXT);
                setBorder(pad);
                setHorizontalAlignment(LEFT);
                mode = null;
                int doneCol = column(t, "Mark Complete");
                boolean rowDone = doneCol >= 0 && Boolean.TRUE.equals(t.getModel().getValueAt(row, doneCol));
                int statusCol = column(t, "Status");
                boolean rowCompleted = statusCol >= 0 && "Completed".equals(t.getModel().getValueAt(row, statusCol));
                switch (column) {
                    case "Course Code":
                        setFont(F_BOLD);
                        setForeground(BLUE);
                        break;
                    case "Task Title":
                        setFont(F_BOLD);
                        if (v instanceof String && ((String) v).contains("\n")) {   // "title\ndetails" -> two lines
                            String[] parts = ((String) v).split("\n", 2);
                            mode = "twoLine";
                            setText(parts[0]);
                            detail = parts[1];
                            setToolTipText(parts[0] + "  \u00b7  " + parts[1]);
                        } else {
                            setToolTipText(null);
                        }
                        break;
                    case "Course Title":
                        setFont(F_BOLD);
                        break;
                    case "Date":
                        if (v instanceof LocalDate && days((LocalDate) v) == 0) {
                            setFont(F_BOLD);
                            setForeground(BLUE);
                            setText("Today  \u00b7  " + getText());
                        }
                        break;
                    case "Due Date":
                        if (v instanceof LocalDate && !rowCompleted) {
                            long d = days((LocalDate) v);
                            if (d < 0) {
                                setForeground(RED);
                                setText(getText() + "  \u00b7  overdue");
                            } else if (d <= 3) {
                                setForeground(AMBER);
                                setText(getText() + "  \u00b7  " + (d == 0 ? "today" : d == 1 ? "tomorrow" : d + " days"));
                            }
                        }
                        break;
                    case "Type":
                        mode = "tag";
                        if ("Exam".equals(v)) useTag(VIOLET_SOFT, VIOLET);
                        else if ("Quiz".equals(v)) useTag(ORANGE_SOFT, ORANGE);
                        else useTag(BLUE_SOFT, BLUE);
                        break;
                    case "Status":
                        mode = "tag";
                        if ("Completed".equals(v)) useTag(GREEN_SOFT, GREEN);
                        else if ("Overdue".equals(v)) useTag(RED_SOFT, RED);
                        else if ("Due soon".equals(v)) useTag(AMBER_SOFT, AMBER);
                        else useTag(new Color(238, 241, 247), MUTED);
                        break;
                    case "Mark Complete":
                        mode = "check";
                        checked = Boolean.TRUE.equals(v);
                        setText(checked ? "Done" : "Mark complete");
                        break;
                    case "Progress":
                        mode = "progress";
                        fraction = v instanceof Double ? (Double) v : 0;
                        break;
                    case "Total Tasks":
                    case "Completed Tasks":
                        setFont(F_BOLD);
                        break;
                    default:
                        break;
                }
                if (rowDone && !"Mark Complete".equals(column)) {
                    setForeground(MUTED);   // finished blocks are greyed out
                }
                return this;
            }

            private void useTag(Color bg, Color fg) {
                tagBg = bg;
                tagFg = fg;
            }

            protected void paintComponent(Graphics g) {
                if (mode == null) {
                    super.paintComponent(g);
                    return;
                }
                Graphics2D g2 = (Graphics2D) g.create();
                smooth(g2);
                g2.setColor(getBackground());
                g2.fillRect(0, 0, getWidth(), getHeight());
                int x = getInsets().left, h = getHeight() - 1;
                if (mode.equals("twoLine")) {
                    int room = getWidth() - x - getInsets().right;
                    g2.setFont(F_BOLD);
                    FontMetrics top = g2.getFontMetrics();
                    g2.setFont(F_SMALL);
                    FontMetrics bottom = g2.getFontMetrics();
                    int y = (h - top.getHeight() - 2 - bottom.getHeight()) / 2;
                    g2.setFont(F_BOLD);
                    g2.setColor(getForeground());
                    g2.drawString(fit(getText(), top, room), x, y + top.getAscent());
                    g2.setFont(F_SMALL);
                    g2.setColor(MUTED);
                    g2.drawString(fit(detail, bottom, room), x, y + top.getHeight() + 2 + bottom.getAscent());
                } else if (mode.equals("tag")) {
                    g2.setFont(new Font(FONT, Font.BOLD, 11));
                    FontMetrics fm = g2.getFontMetrics();
                    int th = 22, w = fm.stringWidth(getText()) + 28, y = (h - th) / 2;
                    g2.setColor(tagBg);
                    g2.fillRoundRect(x, y, w, th, th, th);
                    g2.setColor(tagFg);
                    g2.fillOval(x + 9, y + th / 2 - 3, 6, 6);
                    g2.drawString(getText(), x + 20, y + (th - fm.getHeight()) / 2 + fm.getAscent());
                } else if (mode.equals("check")) {
                    int s = 20, y = (h - s) / 2;
                    if (checked) {
                        g2.setColor(GREEN);
                        g2.fillRoundRect(x, y, s, s, 7, 7);
                        g2.setColor(Color.WHITE);
                        g2.setStroke(new BasicStroke(2.2f, BasicStroke.CAP_ROUND, BasicStroke.JOIN_ROUND));
                        g2.drawPolyline(new int[]{x + 5, x + 9, x + 15}, new int[]{y + 10, y + 14, y + 6}, 3);
                    } else {
                        g2.setColor(Color.WHITE);
                        g2.fillRoundRect(x, y, s, s, 7, 7);
                        g2.setColor(hovered ? BLUE : new Color(190, 199, 216));
                        g2.setStroke(new BasicStroke(1.6f));
                        g2.drawRoundRect(x, y, s, s, 7, 7);
                    }
                    g2.setFont(checked ? F_BOLD : F_TEXT);
                    g2.setColor(checked ? GREEN : MUTED);
                    FontMetrics fm = g2.getFontMetrics();
                    g2.drawString(getText(), x + s + 10, (h - fm.getHeight()) / 2 + fm.getAscent());
                } else {
                    int bw = Math.max(40, Math.min(140, getWidth() - x - 70)), bh = 8, y = (h - bh) / 2;
                    g2.setColor(new Color(233, 237, 245));
                    g2.fillRoundRect(x, y, bw, bh, bh, bh);
                    double f = Math.max(0, Math.min(1, fraction));
                    if (f > 0) {
                        g2.setColor(f >= 0.999 ? GREEN : BLUE);
                        g2.fillRoundRect(x, y, Math.max(bh, (int) Math.round(bw * f)), bh, bh, bh);
                    }
                    g2.setFont(F_BOLD);
                    g2.setColor(INK);
                    FontMetrics fm = g2.getFontMetrics();
                    g2.drawString(Math.round(f * 100) + "%", x + bw + 12, (h - fm.getHeight()) / 2 + fm.getAscent());
                }
                g2.dispose();
            }
        };
    }

    /** Shortens text with "..." so it fits the given width. */
    private static String fit(String s, FontMetrics fm, int width) {
        if (fm.stringWidth(s) <= width) {
            return s;
        }
        String dots = "\u2026";
        int end = s.length();
        while (end > 0 && fm.stringWidth(s.substring(0, end) + dots) > width) {
            end--;
        }
        return s.substring(0, end) + dots;
    }

    /** The text shown in a table cell. Dates leave out the year when it is this year. */
    private static String text(String column, Object v) {
        if (v == null) {
            return "";
        }
        if (v instanceof LocalDate) {
            LocalDate d = (LocalDate) v;
            return d.getYear() == LocalDate.now().getYear() ? SHORT_DAY.format(d) : DAY.format(d);
        }
        if (v instanceof Double && !column.equals("Progress")) {
            return hours((Double) v);
        }
        return String.valueOf(v);
    }

    // ------------------------------------------------------------------ dialogs

    /**
     * Shows a modal dialog in the app's style with the given body, a main button and optionally Cancel.
     * Enter presses the main button and Escape cancels. Returns true when the main button was pressed.
     */
    static boolean dialog(Component parent, String windowTitle, JComponent body, Icon icon, String okText, String okKind,
                          boolean withCancel, final JComponent focus) {
        final JButton ok = button(okText, null, okKind);
        final JButton cancel = withCancel ? button("Cancel", null, "plain") : null;
        Object[] options = withCancel ? new Object[]{cancel, ok} : new Object[]{ok};
        final JOptionPane pane = new JOptionPane(body, JOptionPane.PLAIN_MESSAGE, JOptionPane.DEFAULT_OPTION, icon, options, ok);
        ok.addActionListener(e -> pane.setValue(ok));
        if (cancel != null) {
            cancel.addActionListener(e -> pane.setValue(cancel));
        }
        JDialog d = pane.createDialog(parent, windowTitle);
        d.getRootPane().setDefaultButton(ok);
        if (focus != null) {   // put the cursor in the first field instead of on the button
            d.addWindowFocusListener(new WindowAdapter() {
                private boolean done;

                public void windowGainedFocus(WindowEvent e) {
                    if (!done) {
                        done = true;
                        SwingUtilities.invokeLater(focus::requestFocusInWindow);
                    }
                }
            });
        }
        d.setVisible(true);   // waits here until a button is pressed or the dialog is closed
        d.dispose();
        return pane.getValue() == ok;
    }

    static void warn(Component parent, String message) {
        dialog(parent, "Study Planner", messageBody("Please check", message), badge("warning", 44, AMBER, AMBER_SOFT),
                "OK", "primary", false, null);
    }

    /** Asks before deleting something; returns true when the user pressed Delete. */
    static boolean confirm(Component parent, String heading, String message) {
        return dialog(parent, "Study Planner", messageBody(heading, message + " This cannot be undone."),
                badge("trash", 44, RED, RED_SOFT), "Delete", "danger", true, null);
    }

    /**
     * A form dialog built from pairs of label text and field. Returns true when the main button was pressed.
     * The first text field gets the keyboard focus.
     */
    static boolean form(Component parent, String heading, String intro, String iconKind, String okText, Object... labelsAndFields) {
        JPanel f = new JPanel(new GridBagLayout());
        f.setOpaque(false);
        f.setBorder(new EmptyBorder(0, 2, 6, 2));
        GridBagConstraints c = new GridBagConstraints();
        c.gridx = 0;
        c.weightx = 1;
        c.fill = GridBagConstraints.HORIZONTAL;
        c.anchor = GridBagConstraints.WEST;
        c.gridy = 0;
        c.insets = new Insets(0, 0, 2, 0);
        f.add(label(heading, Font.BOLD, 17, INK), c);
        c.gridy++;
        c.insets = new Insets(0, 0, 8, 0);
        f.add(label(intro, Font.PLAIN, 12, MUTED), c);
        JComponent first = null;
        for (int i = 0; i + 1 < labelsAndFields.length; i += 2) {
            JComponent field = (JComponent) labelsAndFields[i + 1];
            if (first == null) {
                first = field instanceof DatePicker ? ((DatePicker) field).getTextField() : field;
            }
            c.gridy++;
            c.insets = new Insets(8, 0, 5, 0);
            f.add(label((String) labelsAndFields[i], Font.BOLD, 12, INK), c);
            c.gridy++;
            c.insets = new Insets(0, 0, 0, 0);
            f.add(field, c);
        }
        f.setPreferredSize(new Dimension(380, f.getPreferredSize().height));
        return dialog(parent, heading, f, badge(iconKind, 44, BLUE, BLUE_SOFT), okText, "primary", true, first);
    }

    /** Heading plus wrapped message, used by warn() and confirm(). */
    static JPanel messageBody(String heading, String message) {
        JLabel h = label(heading, Font.BOLD, 16, INK);
        JTextArea m = wrapText(message, F_TEXT, MUTED);
        m.setSize(new Dimension(340, 1));
        JPanel p = new JPanel();
        p.setLayout(new BoxLayout(p, BoxLayout.Y_AXIS));
        p.setOpaque(false);
        p.add(fullWidth(h));
        p.add(Box.createVerticalStrut(6));
        p.add(fullWidth(m));
        p.setPreferredSize(new Dimension(340, h.getPreferredSize().height + 6 + m.getPreferredSize().height + 8));
        return p;
    }

    // ------------------------------------------------------------------ icons

    /** Simple line icons drawn on a 20 x 20 grid with Java 2D, so no image files are needed. */
    static Icon icon(final String kind, final int size, final Color color) {
        return new Icon() {
            public int getIconWidth() {
                return size;
            }

            public int getIconHeight() {
                return size;
            }

            public void paintIcon(Component c, Graphics g, int x, int y) {
                Graphics2D g2 = (Graphics2D) g.create();
                smooth(g2);
                g2.translate(x, y);
                g2.scale(size / 20.0, size / 20.0);
                g2.setColor(color);
                g2.setStroke(new BasicStroke(1.7f, BasicStroke.CAP_ROUND, BasicStroke.JOIN_ROUND));
                switch (kind) {
                    case "Dashboard":            // four tiles
                        g2.drawRoundRect(3, 3, 6, 6, 2, 2);
                        g2.drawRoundRect(11, 3, 6, 6, 2, 2);
                        g2.drawRoundRect(3, 11, 6, 6, 2, 2);
                        g2.drawRoundRect(11, 11, 6, 6, 2, 2);
                        break;
                    case "Courses":              // a book
                        g2.drawRoundRect(4, 2, 12, 16, 3, 3);
                        g2.drawLine(7, 2, 7, 18);
                        g2.drawLine(10, 7, 13, 7);
                        break;
                    case "Assessments":          // clipboard with a tick
                        g2.drawRoundRect(4, 3, 12, 15, 3, 3);
                        g2.drawRoundRect(7, 1, 6, 4, 2, 2);
                        g2.drawPolyline(new int[]{7, 9, 13}, new int[]{11, 13, 9}, 3);
                        break;
                    case "Schedule":             // calendar page
                        g2.drawRoundRect(3, 4, 14, 14, 3, 3);
                        g2.drawLine(3, 8, 17, 8);
                        g2.drawLine(7, 2, 7, 5);
                        g2.drawLine(13, 2, 13, 5);
                        g2.fillRect(6, 11, 2, 2);
                        g2.fillRect(9, 11, 2, 2);
                        g2.fillRect(12, 11, 2, 2);
                        g2.fillRect(6, 14, 2, 2);
                        break;
                    case "Clock":
                        g2.drawOval(2, 2, 16, 16);
                        g2.drawPolyline(new int[]{10, 10, 13}, new int[]{6, 10, 12}, 3);
                        break;
                    case "Reports":              // bar chart
                        g2.fillRoundRect(3, 11, 3, 6, 2, 2);
                        g2.fillRoundRect(9, 5, 3, 12, 2, 2);
                        g2.fillRoundRect(15, 8, 3, 9, 2, 2);
                        break;
                    case "plus":
                        g2.drawLine(10, 4, 10, 16);
                        g2.drawLine(4, 10, 16, 10);
                        break;
                    case "edit":                 // pencil
                        g2.drawPolygon(new int[]{4, 13, 16, 7, 3}, new int[]{13, 4, 7, 16, 17}, 5);
                        g2.drawLine(11, 6, 14, 9);
                        break;
                    case "trash":
                        g2.drawLine(3, 5, 17, 5);
                        g2.drawLine(8, 2, 12, 2);
                        g2.drawRoundRect(5, 5, 10, 13, 3, 3);
                        g2.drawLine(8, 9, 8, 14);
                        g2.drawLine(12, 9, 12, 14);
                        break;
                    case "spark":                // "generate": two four-point stars
                        g2.fill(star(9, 11, 7.5, 2.2));
                        g2.fill(star(16, 4, 3.5, 1.1));
                        break;
                    case "check":
                        g2.setStroke(new BasicStroke(2.2f, BasicStroke.CAP_ROUND, BasicStroke.JOIN_ROUND));
                        g2.drawPolyline(new int[]{4, 8, 16}, new int[]{10, 14, 6}, 3);
                        break;
                    case "undo":
                        g2.drawArc(4, 5, 12, 11, -90, 230);
                        g2.drawPolyline(new int[]{3, 5, 8}, new int[]{6, 9, 7}, 3);
                        break;
                    case "warning":
                        g2.drawPolygon(new int[]{10, 18, 2}, new int[]{3, 17, 17}, 3);
                        g2.drawLine(10, 8, 10, 12);
                        g2.fillOval(9, 14, 2, 2);
                        break;
                    case "info":
                        g2.drawOval(2, 2, 16, 16);
                        g2.drawLine(10, 9, 10, 14);
                        g2.fillOval(9, 5, 2, 2);
                        break;
                    case "chevron":
                        g2.drawPolyline(new int[]{5, 10, 15}, new int[]{8, 13, 8}, 3);
                        break;
                    case "chevron-up":
                        g2.drawPolyline(new int[]{5, 10, 15}, new int[]{12, 7, 12}, 3);
                        break;
                    case "left":
                        g2.drawPolyline(new int[]{12, 7, 12}, new int[]{5, 10, 15}, 3);
                        break;
                    case "right":
                        g2.drawPolyline(new int[]{8, 13, 8}, new int[]{5, 10, 15}, 3);
                        break;
                    case "dot":
                        g2.fillOval(4, 4, 12, 12);
                        break;
                    case "logo":                 // gradient tile with a calendar tick
                        g2.setPaint(new GradientPaint(0, 0, new Color(96, 140, 255), 20, 20, VIOLET));
                        g2.fillRoundRect(0, 0, 20, 20, 8, 8);
                        g2.setColor(Color.WHITE);
                        g2.setStroke(new BasicStroke(1.3f, BasicStroke.CAP_ROUND, BasicStroke.JOIN_ROUND));
                        g2.drawRoundRect(5, 6, 10, 9, 3, 3);
                        g2.drawLine(8, 4, 8, 7);
                        g2.drawLine(12, 4, 12, 7);
                        g2.drawPolyline(new int[]{7, 9, 13}, new int[]{11, 13, 9}, 3);
                        break;
                    default:
                        break;
                }
                g2.dispose();
            }
        };
    }

    /** An icon on a soft coloured tile (cards, dialogs and empty states). */
    static Icon badge(final String kind, final int size, final Color fg, final Color bg) {
        final Icon inner = icon(kind, Math.round(size * 0.5f), fg);
        return new Icon() {
            public int getIconWidth() {
                return size;
            }

            public int getIconHeight() {
                return size;
            }

            public void paintIcon(Component c, Graphics g, int x, int y) {
                Graphics2D g2 = (Graphics2D) g.create();
                smooth(g2);
                g2.setColor(bg);
                g2.fillRoundRect(x, y, size, size, size * 3 / 5, size * 3 / 5);
                g2.dispose();
                int o = (size - inner.getIconWidth()) / 2;
                inner.paintIcon(c, g, x + o, y + o);
            }
        };
    }

    private static Shape star(double cx, double cy, double r, double inner) {
        java.awt.geom.Path2D.Double p = new java.awt.geom.Path2D.Double();
        for (int i = 0; i < 8; i++) {
            double angle = Math.PI / 4 * i - Math.PI / 2, radius = i % 2 == 0 ? r : inner;
            double px = cx + Math.cos(angle) * radius, py = cy + Math.sin(angle) * radius;
            if (i == 0) {
                p.moveTo(px, py);
            } else {
                p.lineTo(px, py);
            }
        }
        p.closePath();
        return p;
    }
}
