package studyplanner.ui;

import javax.swing.*;
import javax.swing.border.EmptyBorder;
import java.awt.*;
import java.time.DayOfWeek;
import java.time.LocalDate;
import java.time.YearMonth;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeParseException;
import java.util.Locale;

/**
 * A due-date field: type the date as YYYY-MM-DD or pick it from a small month calendar.
 * Swing has no date picker of its own, so this one is built from plain Swing parts.
 */
@SuppressWarnings("serial")   // Swing parts are never serialized in this program
final class DatePicker extends JPanel {
    private static final DateTimeFormatter MONTH = DateTimeFormatter.ofPattern("MMMM yyyy", Locale.ENGLISH);
    private final JTextField text;
    private final JButton open;
    private YearMonth shown;

    DatePicker(LocalDate initial) {
        super(new BorderLayout(6, 0));
        setOpaque(false);
        text = Ui.field(initial == null ? "" : initial.toString(), "YYYY-MM-DD");
        open = new JButton(Ui.icon("Schedule", 18, Ui.BLUE)) {
            protected void paintComponent(Graphics g) {
                Graphics2D g2 = (Graphics2D) g.create();
                Ui.smooth(g2);
                g2.setColor(getModel().isRollover() ? new Color(220, 231, 255) : Ui.BLUE_SOFT);
                g2.fillRoundRect(0, 0, getWidth() - 1, getHeight() - 1, 10, 10);
                g2.dispose();
                super.paintComponent(g);
            }
        };
        Ui.flat(open);
        open.setToolTipText("Pick a date");
        open.setBorder(new EmptyBorder(0, 10, 0, 10));
        open.addActionListener(e -> showCalendar());
        add(text, BorderLayout.CENTER);
        add(open, BorderLayout.EAST);
    }

    /** The chosen date; throws IllegalArgumentException with a readable message when the text is not a date. */
    LocalDate getDate() {
        try {
            return LocalDate.parse(text.getText().trim());
        } catch (DateTimeParseException e) {
            throw new IllegalArgumentException("Enter the due date as YYYY-MM-DD, for example "
                    + LocalDate.now().plusDays(7) + ", or pick it from the calendar.");
        }
    }

    void setDate(LocalDate date) {
        text.setText(date == null ? "" : date.toString());
    }

    JTextField getTextField() {
        return text;
    }

    private void showCalendar() {
        LocalDate current;
        try {
            current = getDate();
        } catch (IllegalArgumentException e) {
            current = LocalDate.now();
        }
        shown = YearMonth.from(current);
        final JPopupMenu popup = new JPopupMenu();
        popup.setBackground(Color.WHITE);
        popup.setBorder(BorderFactory.createCompoundBorder(BorderFactory.createLineBorder(Ui.LINE), new EmptyBorder(10, 10, 10, 10)));
        final JPanel body = new JPanel(new BorderLayout(0, 8));
        body.setBackground(Color.WHITE);
        fill(body, popup, current);
        popup.add(body);
        Dimension d = popup.getPreferredSize();
        // open below the button, or above it when there is no room below (so it stays inside the window)
        Point at = SwingUtilities.convertPoint(open, 0, 0, getRootPane());
        boolean below = at.y + open.getHeight() + 4 + d.height <= getRootPane().getHeight();
        popup.show(open, open.getWidth() - d.width, below ? open.getHeight() + 4 : -d.height - 4);
    }

    /** Builds the month view: header with arrows, weekday names, day buttons and a Today link. */
    private void fill(final JPanel body, final JPopupMenu popup, final LocalDate selected) {
        body.removeAll();
        JButton prev = arrow("left"), next = arrow("right");
        prev.addActionListener(e -> {
            shown = shown.minusMonths(1);
            fill(body, popup, selected);
        });
        next.addActionListener(e -> {
            shown = shown.plusMonths(1);
            fill(body, popup, selected);
        });
        JLabel month = Ui.label(MONTH.format(shown), Font.BOLD, 14, Ui.INK);
        month.setHorizontalAlignment(SwingConstants.CENTER);
        JPanel head = new JPanel(new BorderLayout());
        head.setOpaque(false);
        head.add(prev, BorderLayout.WEST);
        head.add(month, BorderLayout.CENTER);
        head.add(next, BorderLayout.EAST);

        JPanel grid = new JPanel(new GridLayout(0, 7, 2, 2));
        grid.setOpaque(false);
        for (String w : new String[]{"Mo", "Tu", "We", "Th", "Fr", "Sa", "Su"}) {
            JLabel l = Ui.label(w, Font.BOLD, 11, Ui.MUTED);
            l.setHorizontalAlignment(SwingConstants.CENTER);
            grid.add(l);
        }
        LocalDate first = shown.atDay(1);
        int blanks = first.getDayOfWeek().getValue() - DayOfWeek.MONDAY.getValue();
        for (int i = 0; i < blanks; i++) {
            grid.add(new JLabel());
        }
        for (int day = 1; day <= shown.lengthOfMonth(); day++) {
            final LocalDate date = shown.atDay(day);
            grid.add(dayButton(date, date.equals(selected), date.equals(LocalDate.now()), popup));
        }

        JButton today = new JButton("Today");
        Ui.flat(today);
        today.setFont(Ui.F_BOLD);
        today.setForeground(Ui.BLUE);
        today.addActionListener(e -> {
            setDate(LocalDate.now());
            popup.setVisible(false);
        });
        body.add(head, BorderLayout.NORTH);
        body.add(grid, BorderLayout.CENTER);
        body.add(today, BorderLayout.SOUTH);
        body.revalidate();
        body.repaint();
        popup.pack();
    }

    private JButton dayButton(final LocalDate date, final boolean selected, final boolean isToday, final JPopupMenu popup) {
        JButton b = new JButton(String.valueOf(date.getDayOfMonth())) {
            protected void paintComponent(Graphics g) {
                Graphics2D g2 = (Graphics2D) g.create();
                Ui.smooth(g2);
                if (selected) {
                    g2.setColor(Ui.BLUE);
                    g2.fillRoundRect(1, 1, getWidth() - 2, getHeight() - 2, 10, 10);
                } else if (getModel().isRollover()) {
                    g2.setColor(Ui.BLUE_SOFT);
                    g2.fillRoundRect(1, 1, getWidth() - 2, getHeight() - 2, 10, 10);
                } else if (isToday) {
                    g2.setColor(Ui.BLUE);
                    g2.drawRoundRect(1, 1, getWidth() - 3, getHeight() - 3, 10, 10);
                }
                g2.dispose();
                super.paintComponent(g);
            }
        };
        Ui.flat(b);
        b.setFont(selected || isToday ? Ui.F_BOLD : Ui.F_TEXT);
        b.setForeground(selected ? Color.WHITE : date.isBefore(LocalDate.now()) ? new Color(170, 178, 195) : Ui.INK);
        b.setBorder(new EmptyBorder(6, 0, 6, 0));
        b.setPreferredSize(new Dimension(36, 32));
        b.addActionListener(e -> {
            setDate(date);
            popup.setVisible(false);
            text.requestFocusInWindow();
        });
        return b;
    }

    private static JButton arrow(String kind) {
        JButton b = new JButton(Ui.icon(kind, 16, Ui.INK));
        Ui.flat(b);
        b.setBorder(new EmptyBorder(4, 8, 4, 8));
        return b;
    }
}
