package studyplanner;

import javax.swing.*;
import javax.swing.border.EmptyBorder;
import javax.swing.table.DefaultTableModel;
import java.awt.*;
import java.io.*;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.StandardCopyOption;
import java.time.*;
import java.util.*;

/**
 * Study Planner and Exam Scheduler - OOAD Fall 2026.
 *
 * <p>Layers (all classes live in this one file so the NetBeans setup stays simple):
 * <ul>
 *   <li>Presentation: {@link Main}, {@link PlannerFrame}</li>
 *   <li>Service: {@link PlannerService} (study-plan generation and conflict detection)</li>
 *   <li>Persistence: {@link DataStore}</li>
 *   <li>Model: {@link Course}, {@link Assessment}, {@link StudySession}, {@link ClassMeeting}, {@link PlannerData}</li>
 * </ul>
 */
public class Main {
    /** Starts the Swing user interface on the event dispatch thread. */
    public static void main(String[] args) {
        SwingUtilities.invokeLater(() -> new PlannerFrame().setVisible(true));
    }
}

// ============================================================================ model

/** A university course. */
class Course implements Serializable {
    private static final long serialVersionUID = 1L;
    String code, name, instructor;

    Course(String c, String n, String i) {
        code = c;
        name = n;
        instructor = i;
    }
}

/** An assignment, quiz or exam of a course (type is "Assignment", "Quiz" or "Exam"). */
class Assessment implements Serializable {
    private static final long serialVersionUID = 1L;
    String id, course, title, type, difficulty;   // course = course code; difficulty = Easy / Medium / Hard
    LocalDate deadline;
    double weight;                                // percent of the course grade, 0..100

    Assessment(String id, String c, String t, String ty, LocalDate d, double w, String df) {
        this.id = id;
        course = c;
        title = t;
        type = ty;
        deadline = d;
        weight = w;
        difficulty = df;
    }
}

/** One planned one-hour study session for an assessment. */
class StudySession implements Serializable {
    private static final long serialVersionUID = 1L;
    String id, assessmentId, course;
    LocalDate date;
    LocalTime start, end;
    boolean completed;

    StudySession(String id, String aid, String c, LocalDate d, LocalTime s, LocalTime e) {
        this.id = id;
        assessmentId = aid;
        course = c;
        date = d;
        start = s;
        end = e;
    }
}

/** A recurring weekly class time (day is a java.time.DayOfWeek name such as "MONDAY"). */
class ClassMeeting implements Serializable {
    private static final long serialVersionUID = 1L;
    String course, day;
    LocalTime start, end;

    ClassMeeting(String c, String d, LocalTime s, LocalTime e) {
        course = c;
        day = d;
        start = s;
        end = e;
    }
}

/** Aggregates every object the application stores; this is what is written to disk. */
class PlannerData implements Serializable {
    private static final long serialVersionUID = 1L;
    ArrayList<Course> courses = new ArrayList<>();
    ArrayList<Assessment> assessments = new ArrayList<>();
    ArrayList<StudySession> sessions = new ArrayList<>();
    ArrayList<ClassMeeting> meetings = new ArrayList<>();
}

// ============================================================================ persistence

/** Loads and saves {@link PlannerData} with Java serialization. */
class DataStore {
    private final File file;
    /** Set by {@link #load()} when the data file could not be read; null when everything went well. */
    String loadWarning;

    /** Uses study-planner-data.ser in the user's home directory. */
    DataStore() {
        this(new File(System.getProperty("user.home"), "study-planner-data.ser"));
    }

    /** Uses a specific file (used by the automated tests). */
    DataStore(File file) {
        this.file = file;
    }

    /**
     * Reads the data file. A missing file gives empty data. A damaged file is renamed to
     * *.corrupt (so the next save cannot overwrite it), empty data is returned and
     * {@link #loadWarning} explains what happened.
     */
    PlannerData load() {
        loadWarning = null;
        if (!file.exists()) {
            return new PlannerData();
        }
        // The FileInputStream is its own resource so it is closed even when ObjectInputStream rejects the
        // file header; otherwise the file stays open and Windows refuses to rename it to *.corrupt below.
        try (FileInputStream fin = new FileInputStream(file); ObjectInputStream in = new ObjectInputStream(fin)) {
            return (PlannerData) in.readObject();
        } catch (Exception e) {
            File bad = new File(file.getPath() + ".corrupt");
            bad.delete();
            boolean moved = file.renameTo(bad);
            loadWarning = "The data file could not be read (" + e.getClass().getSimpleName() + "). "
                    + (moved ? "It was kept as " + bad.getName() + " and " : "")
                    + "the planner started with empty data.";
            return new PlannerData();
        }
    }

    /** Writes the data to a temporary file first and then replaces the real file, so a crash cannot leave half a file. */
    void save(PlannerData d) throws IOException {
        File tmp = new File(file.getPath() + ".tmp");
        try (ObjectOutputStream out = new ObjectOutputStream(new FileOutputStream(tmp))) {
            out.writeObject(d);
        }
        try {
            Files.move(tmp.toPath(), file.toPath(), StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
        } catch (AtomicMoveNotSupportedException e) {
            Files.move(tmp.toPath(), file.toPath(), StandardCopyOption.REPLACE_EXISTING);
        }
    }

    String path() {
        return file.getAbsolutePath();
    }
}

// ============================================================================ service

/** Study-plan generation and conflict detection. */
class PlannerService {
    /** Start hours of the three one-hour study slots offered on every weekday (17:00, 18:00, 19:00). */
    static final int[] SLOT_HOURS = {17, 18, 19};
    /** A day with more pending sessions than this is reported as overloaded. */
    static final int MAX_SESSIONS_PER_DAY = 3;

    private final PlannerData d;

    PlannerService(PlannerData data) {
        d = data;
    }

    private boolean overlap(LocalTime a, LocalTime b, LocalTime c, LocalTime e) {
        return a.isBefore(e) && c.isBefore(b);
    }

    /** Number of study sessions wanted for a difficulty: Hard 5, Medium 3, Easy 2. */
    static int targetSessions(String difficulty) {
        return "Hard".equals(difficulty) ? 5 : "Medium".equals(difficulty) ? 3 : 2;
    }

    /** Convenience overload that uses the current time. */
    int generate(Assessment a) {
        return generate(a, LocalDateTime.now());
    }

    /**
     * Creates study sessions for one assessment and returns how many were created.
     *
     * <p>Rules: only weekdays from today up to the day BEFORE the deadline; three possible one-hour slots
     * per day (17:00, 18:00, 19:00); a slot is skipped if it overlaps a class meeting or already holds a
     * pending session; slots that already passed today are skipped. Sessions already completed, or still
     * pending in the future, count towards the target, so pressing Generate again never adds duplicates,
     * while missed (past, unfinished) sessions are planned again. The new sessions are spread evenly over
     * the eligible days, using the first slot of each day before a second slot is used.
     */
    int generate(Assessment a, LocalDateTime now) {
        LocalDate today = now.toLocalDate();
        int needed = targetSessions(a.difficulty) - countCounting(a.id, today);
        int made = 0;
        for (int slot = 0; slot < SLOT_HOURS.length && made < needed; slot++) {
            for (LocalDate day : pick(freeDays(a, slot, now), needed - made)) {
                LocalTime start = LocalTime.of(SLOT_HOURS[slot], 0);
                d.sessions.add(new StudySession(UUID.randomUUID().toString(), a.id, a.course, day, start, start.plusHours(1)));
                made++;
            }
        }
        d.sessions.sort(Comparator.comparing((StudySession s) -> s.date).thenComparing(s -> s.start));
        return made;
    }

    /** Completed sessions plus pending sessions that are still in the future. */
    private int countCounting(String assessmentId, LocalDate today) {
        int n = 0;
        for (StudySession s : d.sessions) {
            if (s.assessmentId.equals(assessmentId) && (s.completed || !s.date.isBefore(today))) {
                n++;
            }
        }
        return n;
    }

    /** Days on which the given slot is available for this assessment, in date order. */
    private java.util.List<LocalDate> freeDays(Assessment a, int slot, LocalDateTime now) {
        java.util.List<LocalDate> out = new ArrayList<>();
        LocalTime start = LocalTime.of(SLOT_HOURS[slot], 0), end = start.plusHours(1);
        for (LocalDate day = now.toLocalDate(); day.isBefore(a.deadline); day = day.plusDays(1)) {
            if (day.getDayOfWeek() == DayOfWeek.SATURDAY || day.getDayOfWeek() == DayOfWeek.SUNDAY) continue;
            if (day.equals(now.toLocalDate()) && !start.isAfter(now.toLocalTime())) continue;
            boolean occupied = false;
            for (StudySession s : d.sessions) {
                if (!s.completed && s.date.equals(day) && s.start.equals(start)) occupied = true;
            }
            for (ClassMeeting m : d.meetings) {
                if (m.day.equals(day.getDayOfWeek().name()) && overlap(start, end, m.start, m.end)) occupied = true;
            }
            if (!occupied) out.add(day);
        }
        return out;
    }

    /** Chooses k evenly spaced entries from a list (all of them when k is at least the list size; the last one when k is 1). */
    private java.util.List<LocalDate> pick(java.util.List<LocalDate> days, int k) {
        if (k >= days.size()) return days;
        java.util.List<LocalDate> out = new ArrayList<>();
        if (k == 1) {
            out.add(days.get(days.size() - 1));
            return out;
        }
        for (int i = 0; i < k; i++) {
            out.add(days.get((int) Math.round(i * (days.size() - 1) / (double) (k - 1))));
        }
        return out;
    }

    /** Human-readable warnings: same-date exams, sessions overlapping a class, overloaded days. */
    java.util.List<String> conflicts() {
        java.util.List<String> out = new ArrayList<>();
        for (int i = 0; i < d.assessments.size(); i++) {
            for (int j = i + 1; j < d.assessments.size(); j++) {
                Assessment a = d.assessments.get(i), b = d.assessments.get(j);
                if (a.type.equals("Exam") && b.type.equals("Exam") && a.deadline.equals(b.deadline)) {
                    out.add("Exam conflict: " + a.title + " and " + b.title + " on " + a.deadline);
                }
            }
        }
        Map<LocalDate, Integer> count = new TreeMap<>();
        for (StudySession s : d.sessions) {
            if (s.completed) continue;
            count.merge(s.date, 1, Integer::sum);
            for (ClassMeeting m : d.meetings) {
                if (m.day.equals(s.date.getDayOfWeek().name()) && overlap(s.start, s.end, m.start, m.end)) {
                    out.add("Class conflict: " + s.course + " session on " + s.date + " overlaps a class.");
                }
            }
        }
        for (Map.Entry<LocalDate, Integer> e : count.entrySet()) {
            if (e.getValue() > MAX_SESSIONS_PER_DAY) {
                out.add("Overloaded day: " + e.getKey() + " has " + e.getValue() + " pending sessions.");
            }
        }
        return out;
    }
}

// ============================================================================ presentation

/**
 * The main window: a sidebar with six pages (Dashboard, Courses, Assessments, Schedule, Class Times, Reports).
 *
 * <p>This class only decides how things look and reacts to the user. The planning rules stay in
 * {@link PlannerService} and saving stays in {@link DataStore}. The visual building blocks (cards, buttons,
 * coloured tags, icons, table styling, dialogs and short "toast" messages) are private helper methods and
 * anonymous classes, so the classes and relationships in the class diagram do not change. Everything is plain
 * Swing and Java 2D: no image files and no third-party libraries.
 */
class PlannerFrame extends JFrame {
    // ---------------------------------------------------------------- theme: colours, fonts and formats in one place

    private static final String FONT = pickFont();
    private static final Color NAVY = new Color(14, 21, 44), NAVY_2 = new Color(27, 38, 76), NAVY_3 = new Color(45, 62, 114);
    private static final Color NAV_TEXT = new Color(178, 191, 220), NAV_MUTED = new Color(112, 129, 170);
    private static final Color BLUE = new Color(59, 110, 230), BLUE_SOFT = new Color(232, 240, 255), BLUE_INK = new Color(36, 68, 150);
    private static final Color GREEN = new Color(16, 148, 90), GREEN_SOFT = new Color(221, 246, 233);
    private static final Color AMBER = new Color(186, 112, 6), AMBER_SOFT = new Color(254, 243, 213);
    private static final Color RED = new Color(208, 46, 62), RED_SOFT = new Color(254, 229, 232);
    private static final Color VIOLET = new Color(118, 78, 220), VIOLET_SOFT = new Color(239, 233, 255);
    private static final Color BG = new Color(243, 246, 251), LINE = new Color(228, 233, 242), ROW_LINE = new Color(238, 241, 247);
    private static final Color INK = new Color(20, 28, 50), MUTED = new Color(104, 115, 140), HOVER = new Color(246, 248, 253);
    private static final Font F_SMALL = new Font(FONT, Font.PLAIN, 12), F_TEXT = new Font(FONT, Font.PLAIN, 13);
    private static final Font F_BOLD = new Font(FONT, Font.BOLD, 13), F_CAPS = caps(11);
    private static final Cursor HAND = Cursor.getPredefinedCursor(Cursor.HAND_CURSOR);
    private static final java.time.format.DateTimeFormatter DAY =
            java.time.format.DateTimeFormatter.ofPattern("EEE d MMM yyyy", Locale.ENGLISH);
    private static final java.time.format.DateTimeFormatter SHORT_DAY =
            java.time.format.DateTimeFormatter.ofPattern("EEE d MMM", Locale.ENGLISH);
    private static final java.time.format.DateTimeFormatter LONG_DAY =
            java.time.format.DateTimeFormatter.ofPattern("EEEE, d MMMM yyyy", Locale.ENGLISH);
    private static final String[] PAGES = {"Dashboard", "Courses", "Assessments", "Schedule", "Class Times", "Reports"};

    // ---------------------------------------------------------------- state

    private PlannerData data;
    private final DataStore store = new DataStore();
    private final PlannerService service;
    private final CardLayout layout = new CardLayout();
    private final JPanel pages = new JPanel(layout);
    private final Map<String, JButton> nav = new LinkedHashMap<>();   // sidebar buttons by page name
    private final Map<String, JLabel> notes = new HashMap<>();        // small texts that refresh() keeps up to date
    private String current = "Dashboard";
    private DefaultTableModel cm, am, sm, tm;   // table models: courses, assessments, sessions, class times
    private JTable ct, at, st, tt;
    private JPanel dashPanel, upcomingList, sessionList, warningList, reportView;
    private JComponent completionBar;
    private JLabel title, subtitle, saveState, toastLabel;
    private javax.swing.Timer toastTimer;

    PlannerFrame() {
        installTheme();
        data = store.load();
        service = new PlannerService(data);
        setTitle("Study Planner and Exam Scheduler");
        Rectangle screen = GraphicsEnvironment.getLocalGraphicsEnvironment().getMaximumWindowBounds();
        setSize(Math.min(1120, screen.width), Math.min(720, screen.height));   // also fits small laptop screens
        setMinimumSize(new Dimension(Math.min(950, screen.width), Math.min(620, screen.height)));
        setDefaultCloseOperation(EXIT_ON_CLOSE);
        setLocationRelativeTo(null);

        JPanel right = new JPanel(new BorderLayout());
        right.setBackground(BG);
        right.add(header(), BorderLayout.NORTH);
        pages.setOpaque(false);
        pages.add(dashboard(), "Dashboard");
        pages.add(courses(), "Courses");
        pages.add(assessments(), "Assessments");
        pages.add(schedule(), "Schedule");
        pages.add(classTimes(), "Class Times");
        pages.add(reports(), "Reports");
        right.add(pages, BorderLayout.CENTER);

        JPanel root = new JPanel(new BorderLayout());
        root.setBackground(BG);
        root.add(sidebar(), BorderLayout.WEST);
        root.add(right, BorderLayout.CENTER);
        setContentPane(root);
        showPage("Dashboard");

        if (store.loadWarning != null) {   // tell the user if the data file was damaged
            SwingUtilities.invokeLater(() -> warn(store.loadWarning));
        }
    }

    // ---------------------------------------------------------------- building the screens

    private JPanel sidebar() {
        JPanel p = new JPanel() {
            protected void paintComponent(Graphics g) {
                Graphics2D g2 = (Graphics2D) g.create();
                g2.setPaint(new GradientPaint(0, 0, NAVY_2, 0, getHeight(), NAVY));
                g2.fillRect(0, 0, getWidth(), getHeight());
                g2.dispose();
            }
        };
        p.setLayout(new BoxLayout(p, BoxLayout.Y_AXIS));
        p.setPreferredSize(new Dimension(232, 0));
        p.setBorder(new EmptyBorder(24, 14, 20, 14));

        JPanel names = new JPanel();
        names.setLayout(new BoxLayout(names, BoxLayout.Y_AXIS));
        names.setOpaque(false);
        names.add(fullWidth(label("Study Planner", Font.BOLD, 16, Color.WHITE)));
        names.add(fullWidth(label("and Exam Scheduler", Font.PLAIN, 12, NAV_TEXT)));
        JPanel brand = new JPanel(new BorderLayout(12, 0));
        brand.setOpaque(false);
        brand.setBorder(new EmptyBorder(0, 6, 0, 0));
        brand.add(new JLabel(icon("logo", 40, Color.WHITE)), BorderLayout.WEST);
        brand.add(names, BorderLayout.CENTER);
        p.add(fullWidth(brand));
        p.add(Box.createVerticalStrut(30));

        JLabel menu = new JLabel("MENU");
        menu.setFont(caps(10));
        menu.setForeground(NAV_MUTED);
        menu.setBorder(new EmptyBorder(0, 16, 8, 0));
        p.add(fullWidth(menu));
        for (String name : PAGES) {
            JButton b = navButton(name);
            nav.put(name, b);
            p.add(fullWidth(b));
            p.add(Box.createVerticalStrut(4));
        }
        p.add(Box.createVerticalGlue());

        saveState = label("Auto-save is on", Font.PLAIN, 12, NAV_TEXT);
        saveState.setIcon(icon("dot", 10, new Color(74, 222, 128)));
        saveState.setIconTextGap(8);
        JLabel course = new JLabel("OOAD  \u2022  FALL 2026");
        course.setFont(caps(10));
        course.setForeground(NAV_MUTED);
        JPanel foot = new JPanel();
        foot.setLayout(new BoxLayout(foot, BoxLayout.Y_AXIS));
        foot.setOpaque(false);
        foot.setBorder(BorderFactory.createCompoundBorder(
                BorderFactory.createMatteBorder(1, 0, 0, 0, new Color(255, 255, 255, 26)), new EmptyBorder(14, 10, 0, 0)));
        foot.add(fullWidth(saveState));
        foot.add(Box.createVerticalStrut(6));
        foot.add(fullWidth(course));
        p.add(fullWidth(foot));
        return p;
    }

    /** A sidebar entry; the current page gets a lighter background and a blue accent bar. */
    private JButton navButton(final String name) {
        JButton b = new JButton(name) {
            protected void paintComponent(Graphics g) {
                Graphics2D g2 = (Graphics2D) g.create();
                smooth(g2);
                if (name.equals(current)) {
                    g2.setColor(NAVY_3);
                    g2.fillRoundRect(0, 0, getWidth(), getHeight(), 12, 12);
                    g2.setColor(new Color(118, 156, 255));
                    g2.fillRoundRect(0, 11, 4, getHeight() - 22, 4, 4);
                } else if (getModel().isRollover()) {
                    g2.setColor(new Color(255, 255, 255, 16));
                    g2.fillRoundRect(0, 0, getWidth(), getHeight(), 12, 12);
                }
                g2.dispose();
                super.paintComponent(g);
            }
        };
        b.setHorizontalAlignment(SwingConstants.LEFT);
        b.setIconTextGap(12);
        b.setFont(new Font(FONT, Font.PLAIN, 14));
        b.setForeground(NAV_TEXT);
        b.setIcon(icon(name, 18, NAV_TEXT));
        flat(b);
        b.setFocusable(false);
        b.setBorder(new EmptyBorder(11, 16, 11, 12));
        b.addActionListener(e -> showPage(name));
        return b;
    }

    private JPanel header() {
        title = label("Dashboard", Font.BOLD, 24, INK);
        subtitle = label(" ", Font.PLAIN, 13, MUTED);
        JPanel text = new JPanel();
        text.setLayout(new BoxLayout(text, BoxLayout.Y_AXIS));
        text.setOpaque(false);
        text.add(fullWidth(title));
        text.add(Box.createVerticalStrut(2));
        text.add(fullWidth(subtitle));

        JLabel date = pill(LONG_DAY.format(LocalDate.now()), icon("Schedule", 16, BLUE), BLUE_SOFT, BLUE_INK);
        date.setBorder(new EmptyBorder(8, 14, 8, 16));
        JPanel east = new JPanel(new GridBagLayout());
        east.setOpaque(false);
        east.add(date);

        JPanel h = new JPanel(new BorderLayout(20, 0));
        h.setBackground(Color.WHITE);
        h.setBorder(BorderFactory.createCompoundBorder(
                BorderFactory.createMatteBorder(0, 0, 1, 0, LINE), new EmptyBorder(16, 28, 16, 28)));
        h.add(text, BorderLayout.CENTER);
        h.add(east, BorderLayout.EAST);
        return h;
    }

    private JPanel dashboard() {
        JPanel p = page();
        JPanel stats = new JPanel(new GridLayout(1, 4, 16, 0));
        stats.setOpaque(false);
        stats.add(statCard("Courses", "Courses", BLUE, BLUE_SOFT));
        stats.add(statCard("Assessments", "Assessments", VIOLET, VIOLET_SOFT));
        stats.add(statCard("Pending Sessions", "Class Times", AMBER, AMBER_SOFT));
        stats.add(statCard("Completed Sessions", "check", GREEN, GREEN_SOFT));

        upcomingList = stack();
        sessionList = stack();
        warningList = stack();
        JPanel side = new JPanel(new GridLayout(2, 1, 0, 16));
        side.setOpaque(false);
        side.add(section("Next study sessions", note("sessions"), scrollList(sessionList)));
        side.add(section("Scheduling warnings", note("warnings"), scrollList(warningList)));
        JPanel lower = new JPanel(new GridLayout(1, 2, 16, 0));
        lower.setOpaque(false);
        lower.add(section("Upcoming deadlines", note("deadlines"), scrollList(upcomingList)));
        lower.add(side);

        p.add(stats, BorderLayout.NORTH);
        p.add(lower, BorderLayout.CENTER);
        dashPanel = p;
        return p;
    }

    /** A dashboard number card; the number label is named "stat:" + name so setStat() can find it. */
    private JPanel statCard(String name, String iconKind, Color accent, Color soft) {
        JLabel caption = new JLabel(name.toUpperCase(Locale.ENGLISH));
        caption.setFont(caps(10));
        caption.setForeground(MUTED);
        JLabel value = label("0", Font.BOLD, 30, INK);
        value.setName("stat:" + name);
        JPanel mid = new JPanel(new BorderLayout());
        mid.setOpaque(false);
        mid.add(value, BorderLayout.WEST);
        mid.add(new JLabel(badge(iconKind, 40, accent, soft)), BorderLayout.EAST);

        Component meter;   // only the completed card has a progress bar; the others keep the same spacing
        if (name.equals("Completed Sessions")) {
            completionBar = bar(GREEN);
            meter = completionBar;
        } else {
            meter = Box.createRigidArea(new Dimension(0, 6));
        }
        JPanel south = new JPanel(new BorderLayout(0, 8));
        south.setOpaque(false);
        south.add(meter, BorderLayout.NORTH);
        JLabel sub = note("sub:" + name);
        sub.setBorder(new EmptyBorder(0, 0, 0, 4));   // line up with the caption above
        south.add(sub, BorderLayout.CENTER);

        JPanel c = card(new BorderLayout(0, 4));
        c.setBorder(new EmptyBorder(16, 16, 20, 16));
        c.add(caption, BorderLayout.NORTH);
        c.add(mid, BorderLayout.CENTER);
        c.add(south, BorderLayout.SOUTH);
        return c;
    }

    private JPanel courses() {
        JPanel p = page();
        cm = model("Code", "Course Name", "Instructor");
        ct = table(cm, "Courses", "Click Add Course to add your first course.");
        widths(ct, 110, 320, 220);
        JButton add = button("Add Course", "plus", "primary"), del = button("Delete Selected", "trash", "soft-danger");
        add.addActionListener(e -> addCourse());
        del.addActionListener(e -> deleteCourse());
        onDelete(ct, this::deleteCourse);
        p.add(top(toolbar("Courses", add, del)), BorderLayout.NORTH);
        p.add(tableCard(ct), BorderLayout.CENTER);
        return p;
    }

    private JPanel assessments() {
        JPanel p = page();
        am = model("Title", "Course", "Type", "Deadline", "Weight %", "Difficulty");
        at = table(am, "Assessments", "Add a course first, then click Add Assessment.");
        widths(at, 220, 80, 120, 190, 100, 110);
        JButton add = button("Add Assessment", "plus", "primary"), del = button("Delete Selected", "trash", "soft-danger");
        add.addActionListener(e -> addAssessment());
        del.addActionListener(e -> deleteAssessment());
        onDelete(at, this::deleteAssessment);
        p.add(top(toolbar("Assessments", add, del),
                hint("Hard assessments get 5 study sessions, Medium 3 and Easy 2. Close and overdue deadlines are highlighted.")),
                BorderLayout.NORTH);
        p.add(tableCard(at), BorderLayout.CENTER);
        return p;
    }

    private JPanel schedule() {
        JPanel p = page();
        sm = model("Date", "Time", "Course", "Assessment", "Status");
        st = table(sm, "Schedule", "Add assessments, then press Generate Study Plan.");
        widths(st, 190, 150, 80, 230, 120);
        JButton gen = button("Generate Study Plan", "spark", "primary"), done = button("Mark Complete", "check", "success");
        gen.addActionListener(e -> generate());
        done.addActionListener(e -> complete());
        st.addMouseListener(new java.awt.event.MouseAdapter() {
            public void mouseClicked(java.awt.event.MouseEvent e) {
                if (e.getClickCount() == 2 && st.rowAtPoint(e.getPoint()) >= 0) complete();
            }
        });
        p.add(top(toolbar("Schedule", gen, done),
                hint("One-hour sessions at 17:00, 18:00 and 19:00 on weekdays before each deadline. Double-click a session to complete it.")),
                BorderLayout.NORTH);
        p.add(tableCard(st), BorderLayout.CENTER);
        return p;
    }

    private JPanel classTimes() {
        JPanel p = page();
        tm = model("Course", "Day", "Start", "End");
        tt = table(tm, "Class Times", "Click Add Class Time to enter your weekly classes.");
        widths(tt, 110, 160, 110, 110);
        JButton add = button("Add Class Time", "plus", "primary"), del = button("Delete Selected", "trash", "soft-danger");
        add.addActionListener(e -> addMeeting());
        del.addActionListener(e -> deleteMeeting());
        onDelete(tt, this::deleteMeeting);
        p.add(top(toolbar("Class Times", add, del),
                hint("Study sessions are never planned during these weekly class times.")), BorderLayout.NORTH);
        p.add(tableCard(tt), BorderLayout.CENTER);
        return p;
    }

    private JPanel reports() {
        JPanel p = page();
        JButton make = button("Generate Reports", "Reports", "primary"), copy = button("Copy as Text", "copy", "plain");
        make.addActionListener(e -> {
            renderReport();
            toast("Report updated");
        });
        copy.addActionListener(e -> {
            Toolkit.getDefaultToolkit().getSystemClipboard().setContents(new java.awt.datatransfer.StringSelection(report()), null);
            toast("Report copied to the clipboard as text");
        });
        reportView = stack();
        p.add(top(toolbar("Reports", make, copy)), BorderLayout.NORTH);
        p.add(scrollList(reportView), BorderLayout.CENTER);
        return p;
    }

    // ---------------------------------------------------------------- user actions

    private void addCourse() {
        JTextField code = field("", "e.g. CS302"), name = field("", "e.g. Object-Oriented Analysis and Design"),
                inst = field("", "e.g. Dr. Yazeed Ghadi");
        if (form("Add Course", "Courses group your assessments and class times.", "Courses",
                "Course code", code, "Course name", name, "Instructor", inst)) {
            if (code.getText().trim().isEmpty() || name.getText().trim().isEmpty()) {
                warn("Course code and name are required.");
                return;
            }
            for (Course c : data.courses) {
                if (c.code.equalsIgnoreCase(code.getText().trim())) {
                    warn("Course code already exists.");
                    return;
                }
            }
            data.courses.add(new Course(code.getText().trim(), name.getText().trim(), inst.getText().trim()));
            save();
            refresh();
            toast("Course " + code.getText().trim() + " added");
        }
    }

    /** Deletes the selected course and its class times; refused while the course still has assessments. */
    private void deleteCourse() {
        int r = ct.getSelectedRow();
        if (r < 0) {
            warn("Select a course.");
            return;
        }
        String code = (String) cm.getValueAt(r, 0);
        for (Assessment a : data.assessments) {
            if (a.course.equals(code)) {
                warn("Delete this course's assessments first.");
                return;
            }
        }
        int classes = 0;
        for (ClassMeeting m : data.meetings) if (m.course.equals(code)) classes++;
        if (!confirm("Delete course?", code + " will be deleted"
                + (classes > 0 ? " together with its " + plural(classes, "class time", "class times") : "") + ".")) {
            return;
        }
        data.courses.remove(r);
        data.meetings.removeIf(m -> m.course.equals(code));   // no orphan class times
        save();
        refresh();
        toast("Course " + code + " deleted");
    }

    private void addAssessment() {
        if (data.courses.isEmpty()) {
            warn("Add a course first.");
            return;
        }
        JComboBox<String> course = new JComboBox<>();
        for (Course c : data.courses) course.addItem(c.code + " - " + c.name);
        JTextField name = field("", "e.g. Midterm Exam"), date = field(LocalDate.now().plusDays(7).toString(), "YYYY-MM-DD"),
                weight = field("10", "0 to 100");
        JComboBox<String> type = new JComboBox<>(new String[]{"Assignment", "Quiz", "Exam"});
        JComboBox<String> diff = new JComboBox<>(new String[]{"Easy", "Medium", "Hard"});
        if (form("Add Assessment", "Hard gets 5 study sessions, Medium 3 and Easy 2.", "Assessments",
                "Course", course, "Title", name, "Type", type, "Deadline (YYYY-MM-DD)", date,
                "Weight (0-100)", weight, "Difficulty", diff)) {
            try {
                String cc = ((String) course.getSelectedItem()).split(" - ")[0];
                LocalDate dl = LocalDate.parse(date.getText().trim());
                double w = Double.parseDouble(weight.getText().trim());
                if (name.getText().trim().isEmpty() || w < 0 || w > 100) throw new Exception();
                data.assessments.add(new Assessment(UUID.randomUUID().toString(), cc, name.getText().trim(),
                        (String) type.getSelectedItem(), dl, w, (String) diff.getSelectedItem()));
                save();
                refresh();
                toast("Assessment added \u2013 open Schedule to plan study sessions");
            } catch (Exception ex) {
                warn("Enter a title, valid date, and weight from 0 to 100.");
            }
        }
    }

    /** Deletes the SELECTED row (by position, so two assessments with the same title cannot be mixed up) and its sessions. */
    private void deleteAssessment() {
        int r = at.getSelectedRow();
        if (r < 0) {
            warn("Select an assessment.");
            return;
        }
        Assessment target = data.assessments.get(r);
        int linked = 0;
        for (StudySession s : data.sessions) if (s.assessmentId.equals(target.id)) linked++;
        if (!confirm("Delete assessment?", "\"" + target.title + "\" (" + target.course + ") will be deleted"
                + (linked > 0 ? " together with its " + plural(linked, "study session", "study sessions") : "") + ".")) {
            return;
        }
        data.assessments.remove(r);
        data.sessions.removeIf(s -> s.assessmentId.equals(target.id));
        save();
        refresh();
        toast("Assessment deleted");
    }

    private void addMeeting() {
        if (data.courses.isEmpty()) {
            warn("Add a course first.");
            return;
        }
        JComboBox<String> course = new JComboBox<>();
        for (Course c : data.courses) course.addItem(c.code);
        JComboBox<String> day = new JComboBox<>(new String[]{"Monday", "Tuesday", "Wednesday", "Thursday", "Friday", "Saturday", "Sunday"});
        JTextField start = field("09:00", "HH:MM"), end = field("10:00", "HH:MM");
        if (form("Add Class Time", "Study sessions will never be planned during this time.", "Class Times",
                "Course", course, "Day", day, "Start (HH:MM)", start, "End (HH:MM)", end)) {
            try {
                LocalTime s = LocalTime.parse(start.getText().trim()), e = LocalTime.parse(end.getText().trim());
                if (!s.isBefore(e)) throw new Exception();
                String dayName = ((String) day.getSelectedItem()).toUpperCase(Locale.ENGLISH);   // stored as "MONDAY" etc.
                data.meetings.add(new ClassMeeting((String) course.getSelectedItem(), dayName, s, e));
                save();
                refresh();
                toast("Class time added");
            } catch (Exception ex) {
                warn("Enter valid times; end must be after start.");
            }
        }
    }

    private void deleteMeeting() {
        int r = tt.getSelectedRow();
        if (r < 0) {
            warn("Select a class time.");
            return;
        }
        ClassMeeting m = data.meetings.get(r);
        if (!confirm("Delete class time?", m.course + " on " + cap(m.day) + " " + m.start + " \u2013 " + m.end + " will be removed.")) {
            return;
        }
        data.meetings.remove(r);
        save();
        refresh();
        toast("Class time deleted");
    }

    private void generate() {
        if (data.assessments.isEmpty()) {
            warn("Add an assessment first.");
            return;
        }
        int n = 0;
        for (Assessment a : data.assessments) n += service.generate(a);
        save();
        refresh();
        toast(n == 0 ? "Your plan is already up to date" : plural(n, "new study session", "new study sessions") + " planned");
    }

    private void complete() {
        int r = st.getSelectedRow();
        if (r < 0) {
            warn("Select a session.");
            return;
        }
        StudySession s = data.sessions.get(r);   // table rows are in the same order as the list
        if (s.completed) {
            toast("That session is already completed");
            return;
        }
        s.completed = true;
        save();
        refresh();
        if (r < st.getRowCount()) st.setRowSelectionInterval(r, r);
        toast("Session marked complete \u2013 nice work!");
    }

    // ---------------------------------------------------------------- refreshing the views

    /** Switches to a page, updates the header and the sidebar highlight, then refreshes the data. */
    private void showPage(String name) {
        current = name;
        layout.show(pages, name);
        title.setText(name);
        subtitle.setText(subtitleOf(name));
        for (Map.Entry<String, JButton> e : nav.entrySet()) {
            boolean on = e.getKey().equals(name);
            JButton b = e.getValue();
            b.setForeground(on ? Color.WHITE : NAV_TEXT);
            b.setFont(new Font(FONT, on ? Font.BOLD : Font.PLAIN, 14));
            b.setIcon(icon(e.getKey(), 18, on ? Color.WHITE : NAV_TEXT));
            b.repaint();
        }
        refresh();
    }

    private static String subtitleOf(String page) {
        switch (page) {
            case "Dashboard": return "Your semester at a glance";
            case "Courses": return "The courses you are taking this semester";
            case "Assessments": return "Assignments, quizzes and exams with their deadlines";
            case "Schedule": return "Study sessions planned before each deadline";
            case "Class Times": return "Your weekly classes \u2013 study sessions never overlap them";
            default: return "Progress for the next 7 days and workload per course";
        }
    }

    private void refresh() {
        cm.setRowCount(0);
        for (Course c : data.courses) cm.addRow(new Object[]{c.code, c.name, c.instructor});
        am.setRowCount(0);
        for (Assessment a : data.assessments) am.addRow(new Object[]{a.title, a.course, a.type, a.deadline, a.weight, a.difficulty});
        sm.setRowCount(0);
        for (StudySession s : data.sessions) {
            Assessment a = find(s.assessmentId);
            sm.addRow(new Object[]{s.date, s.start + "-" + s.end, s.course, a == null ? "(deleted)" : a.title, s.completed ? "Completed" : "Pending"});
        }
        tm.setRowCount(0);
        for (ClassMeeting m : data.meetings) tm.addRow(new Object[]{m.course, m.day, m.start, m.end});

        int overdue = 0;
        for (Assessment a : data.assessments) if (days(a.deadline) < 0) overdue++;
        setNote("Courses", plural(data.courses.size(), "course", "courses"));
        setNote("Assessments", plural(data.assessments.size(), "assessment", "assessments") + (overdue > 0 ? "  \u00b7  " + overdue + " overdue" : ""));
        setNote("Schedule", count(false) + " pending  \u00b7  " + count(true) + " completed");
        setNote("Class Times", plural(data.meetings.size(), "weekly class", "weekly classes"));
        updateDashboard();
        if ("Reports".equals(current)) renderReport();
    }

    private Assessment find(String id) {
        for (Assessment a : data.assessments) if (a.id.equals(id)) return a;
        return null;
    }

    private void updateDashboard() {
        int pending = count(false), done = count(true);
        if (dashPanel != null) {
            setStat(dashPanel, "Courses", data.courses.size());
            setStat(dashPanel, "Assessments", data.assessments.size());
            setStat(dashPanel, "Pending Sessions", pending);
            setStat(dashPanel, "Completed Sessions", done);
        }
        int dueSoon = 0, overdue = 0, today = 0, week = 0;
        for (Assessment a : data.assessments) {
            long d = days(a.deadline);
            if (d < 0) overdue++;
            else if (d <= 7) dueSoon++;
        }
        for (StudySession s : data.sessions) {
            long d = days(s.date);
            if (!s.completed && d == 0) today++;
            if (!s.completed && d >= 0 && d <= 6) week++;
        }
        setNote("sub:Courses", plural(data.meetings.size(), "weekly class", "weekly classes"));
        setNote("sub:Assessments", (overdue > 0 ? overdue + " overdue  \u00b7  " + dueSoon + " this week" : dueSoon + " due this week"));
        setNote("sub:Pending Sessions", today + " today  \u00b7  " + week + " this week");
        int total = pending + done;
        setNote("sub:Completed Sessions", total == 0 ? "No sessions yet" : Math.round(100.0 * done / total) + "% of all sessions");
        if (completionBar != null) {
            completionBar.putClientProperty("value", total == 0 ? 0.0 : done / (double) total);
            completionBar.repaint();
        }

        // Upcoming deadlines: due within seven days, or overdue.
        upcomingList.removeAll();
        java.util.List<Assessment> soon = new ArrayList<>();
        for (Assessment a : data.assessments) if (days(a.deadline) <= 7) soon.add(a);
        soon.sort(Comparator.comparing((Assessment a) -> a.deadline));
        for (Assessment a : soon) {
            long d = days(a.deadline);
            JLabel tag = d < 0 ? chip("OVERDUE", RED_SOFT, RED)
                    : d == 0 ? chip("TODAY", RED_SOFT, RED)
                    : d == 1 ? chip("TOMORROW", AMBER_SOFT, AMBER)
                    : d <= 3 ? chip("IN " + d + " DAYS", AMBER_SOFT, AMBER)
                    : chip("IN " + d + " DAYS", BLUE_SOFT, BLUE);
            upcomingList.add(listRow(tag, 86, a.title, a.course + "  \u00b7  " + a.type + "  \u00b7  " + DAY.format(a.deadline)
                    + "  \u00b7  " + weightText(a.weight) + " of the grade"));
        }
        if (soon.isEmpty()) {
            upcomingList.add(emptyRow("check", GREEN, GREEN_SOFT, "Nothing due in the next 7 days",
                    "New assignments, quizzes and exams will show up here."));
        }
        setNote("deadlines", soon.isEmpty() ? "" : plural(soon.size(), "item", "items"));

        // Next study sessions that have not happened yet.
        sessionList.removeAll();
        java.util.List<StudySession> next = new ArrayList<>(data.sessions);
        next.sort(Comparator.comparing((StudySession s) -> s.date).thenComparing(s -> s.start));
        int shown = 0;
        LocalTime now = LocalTime.now();
        for (StudySession s : next) {
            long d = days(s.date);
            if (s.completed || d < 0 || (d == 0 && !s.end.isAfter(now))) continue;
            Assessment a = find(s.assessmentId);
            sessionList.add(listRow(dateBlock(s.date), 52, a == null ? "(deleted)" : a.title,
                    s.course + "  \u00b7  " + s.start + " \u2013 " + s.end));
            if (++shown == 6) break;
        }
        if (shown == 0) {
            sessionList.add(emptyRow("Schedule", BLUE, BLUE_SOFT, "No study sessions planned",
                    "Open Schedule and press Generate Study Plan."));
        }
        setNote("sessions", week == 0 ? "" : week + " this week");

        // Conflicts found by the service.
        warningList.removeAll();
        java.util.List<String> warnings = service.conflicts();
        for (String w : warnings) warningList.add(warningRow(w));
        if (warnings.isEmpty()) {
            warningList.add(emptyRow("check", GREEN, GREEN_SOFT, "No conflicts detected",
                    "Exams, classes and study sessions fit together."));
        }
        setNote("warnings", warnings.isEmpty() ? "" : plural(warnings.size(), "warning", "warnings"));
        relayout(upcomingList, sessionList, warningList);
    }

    /** Draws the Reports page: weekly summary, workload per course and conflicts (the same numbers as report()). */
    private void renderReport() {
        reportView.removeAll();
        LocalDate today = LocalDate.now();
        int[] week = weekCounts();
        int n = week[0], done = week[1];

        JPanel numbers = new JPanel(new GridLayout(1, 3, 16, 0));
        numbers.setOpaque(false);
        numbers.add(metric("Sessions", n, INK));
        numbers.add(metric("Completed", done, GREEN));
        numbers.add(metric("Pending", n - done, AMBER));
        JComponent progress = bar(GREEN);
        progress.putClientProperty("value", n == 0 ? 0.0 : done / (double) n);
        progress.setPreferredSize(new Dimension(100, 8));
        JPanel summary = new JPanel(new BorderLayout(0, 8));
        summary.setOpaque(false);
        summary.add(numbers, BorderLayout.NORTH);
        summary.add(progress, BorderLayout.CENTER);
        summary.add(label(n == 0 ? "No study sessions in the next 7 days." : Math.round(100.0 * done / n)
                + "% of this week's sessions are done.", Font.PLAIN, 12, MUTED), BorderLayout.SOUTH);
        JLabel range = label(SHORT_DAY.format(today) + "  \u2013  " + SHORT_DAY.format(today.plusDays(7)), Font.PLAIN, 12, MUTED);
        range.setBorder(new EmptyBorder(0, 8, 0, 4));
        reportView.add(section("Weekly summary", range, summary));
        reportView.add(Box.createVerticalStrut(16));

        JPanel rows = stack();
        for (Course c : data.courses) {
            int tasks = 0, left = 0;
            double weight = 0;
            for (Assessment a : data.assessments) {
                if (a.course.equals(c.code)) {
                    tasks++;
                    weight += a.weight;
                }
            }
            for (StudySession s : data.sessions) if (s.course.equals(c.code) && !s.completed) left++;
            rows.add(workloadRow(c, tasks, weight, left));
        }
        if (data.courses.isEmpty()) {
            rows.add(emptyRow("Courses", BLUE, BLUE_SOFT, "No courses yet", "Add courses to see their workload here."));
        }
        JLabel weightNote = label("combined listed weight per course", Font.PLAIN, 12, MUTED);
        weightNote.setBorder(new EmptyBorder(0, 8, 0, 4));
        reportView.add(section("Course workload", weightNote, rows));
        reportView.add(Box.createVerticalStrut(16));

        JPanel conflicts = stack();
        java.util.List<String> w = service.conflicts();
        for (String s : w) conflicts.add(warningRow(s));
        if (w.isEmpty()) {
            conflicts.add(emptyRow("check", GREEN, GREEN_SOFT, "No conflicts detected", "Exams, classes and study sessions fit together."));
        }
        reportView.add(section("Conflicts", null, conflicts));
        setNote("Reports", "Updated at " + LocalTime.now().withSecond(0).withNano(0));
        relayout(reportView);
    }

    private void setStat(Container c, String key, int val) {
        for (Component x : c.getComponents()) {
            if (x instanceof JLabel && ("stat:" + key).equals(x.getName())) ((JLabel) x).setText("" + val);
            if (x instanceof Container) setStat((Container) x, key, val);
        }
    }

    private int count(boolean done) {
        int n = 0;
        for (StudySession s : data.sessions) if (s.completed == done) n++;
        return n;
    }

    /** Sessions in the next 7 days and how many of them are completed: {sessions, completed}. */
    private int[] weekCounts() {
        int n = 0, done = 0;
        for (StudySession s : data.sessions) {
            if (!s.date.isBefore(LocalDate.now()) && !s.date.isAfter(LocalDate.now().plusDays(7))) {
                n++;
                if (s.completed) done++;
            }
        }
        return new int[]{n, done};
    }

    /** Builds the text of the weekly summary, the per-course workload and the current warnings (used by "Copy as Text"). */
    private String report() {
        StringBuilder b = new StringBuilder("STUDY PLANNER REPORT \u2014 " + LocalDate.now() + "\n\nWEEKLY SUMMARY (next 7 days)\n");
        int[] week = weekCounts();
        int n = week[0], done = week[1];
        b.append("Sessions: ").append(n).append("\nCompleted: ").append(done).append("\nPending: ").append(n - done).append("\n\nCOURSE WORKLOAD\n");
        for (Course c : data.courses) {
            int tasks = 0;
            double weight = 0;
            for (Assessment a : data.assessments) {
                if (a.course.equals(c.code)) {
                    tasks++;
                    weight += a.weight;
                }
            }
            b.append(c.code).append(" ").append(c.name).append(": ").append(tasks).append(" assessments, combined listed weight ").append(weight).append("%\n");
        }
        b.append("\nCONFLICTS\n");
        java.util.List<String> w = service.conflicts();
        if (w.isEmpty()) b.append("No conflicts detected.\n");
        else for (String s : w) b.append("- ").append(s).append("\n");
        return b.toString();
    }

    private void save() {
        try {
            store.save(data);
            saveState.setText("All changes saved  \u00b7  " + LocalTime.now().withSecond(0).withNano(0));
            saveState.setIcon(icon("dot", 10, new Color(74, 222, 128)));
        } catch (IOException e) {
            saveState.setText("Not saved");
            saveState.setIcon(icon("dot", 10, new Color(248, 113, 113)));
            warn("Could not save: " + e.getMessage());
        }
    }

    // ---------------------------------------------------------------- dialogs and messages

    private void warn(String s) {
        dialog("Study Planner", messageBody("Please check", s), badge("warning", 44, AMBER, AMBER_SOFT), "OK", "primary", false, null);
    }

    /** Asks before deleting something; returns true when the user pressed Delete. */
    private boolean confirm(String heading, String message) {
        return dialog("Study Planner", messageBody(heading, message + " This cannot be undone."),
                badge("trash", 44, RED, RED_SOFT), "Delete", "danger", true, null);
    }

    /** A form dialog built from pairs of label text and field. Returns true when the main button was pressed. */
    private boolean form(String heading, String intro, String iconKind, Object... labelsAndFields) {
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
            styleField(field);
            if (first == null && field instanceof JTextField) first = field;
            c.gridy++;
            c.insets = new Insets(8, 0, 5, 0);
            f.add(label((String) labelsAndFields[i], Font.BOLD, 12, INK), c);
            c.gridy++;
            c.insets = new Insets(0, 0, 0, 0);
            f.add(field, c);
        }
        f.setPreferredSize(new Dimension(380, f.getPreferredSize().height));
        return dialog(heading, f, badge(iconKind, 44, BLUE, BLUE_SOFT), heading, "primary", true, first);
    }

    /**
     * Shows a modal dialog in the app's style with the given body, a main button and optionally Cancel.
     * Enter presses the main button and Escape cancels. Returns true when the main button was pressed.
     */
    private boolean dialog(String windowTitle, JComponent body, Icon icon, String okText, String okKind,
                           boolean withCancel, final JComponent focus) {
        final JButton ok = button(okText, null, okKind);
        final JButton cancel = withCancel ? button("Cancel", null, "plain") : null;
        Object[] options = withCancel ? new Object[]{cancel, ok} : new Object[]{ok};
        final JOptionPane pane = new JOptionPane(body, JOptionPane.PLAIN_MESSAGE, JOptionPane.DEFAULT_OPTION, icon, options, ok);
        ok.addActionListener(e -> pane.setValue(ok));
        if (cancel != null) cancel.addActionListener(e -> pane.setValue(cancel));
        JDialog d = pane.createDialog(this, windowTitle);
        d.getRootPane().setDefaultButton(ok);
        if (focus != null) {   // put the cursor in the first field instead of on the button
            d.addWindowFocusListener(new java.awt.event.WindowAdapter() {
                private boolean done;

                public void windowGainedFocus(java.awt.event.WindowEvent e) {
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

    /** A short message that slides in at the bottom of the window and disappears by itself. */
    private void toast(String message) {
        final JLayeredPane lp = getLayeredPane();
        if (toastLabel != null) lp.remove(toastLabel);
        if (toastTimer != null) toastTimer.stop();
        final JLabel t = pill(message, icon("check", 16, new Color(110, 231, 160)), new Color(17, 24, 48, 238), Color.WHITE);
        t.setFont(F_BOLD);
        t.setIconTextGap(10);
        t.setBorder(new EmptyBorder(12, 16, 12, 20));
        Dimension d = t.getPreferredSize();
        Rectangle area = SwingUtilities.convertRectangle(pages.getParent(), pages.getBounds(), lp);
        t.setBounds(area.x + (area.width - d.width) / 2, area.y + area.height - d.height - 26, d.width, d.height);
        lp.add(t, JLayeredPane.POPUP_LAYER);
        lp.repaint();
        toastLabel = t;
        toastTimer = new javax.swing.Timer(2800, e -> {
            lp.remove(t);
            lp.repaint();
            if (toastLabel == t) toastLabel = null;
        });
        toastTimer.setRepeats(false);
        toastTimer.start();
    }

    // ---------------------------------------------------------------- visual building blocks (no app logic below)

    /** Applies the app font and calmer defaults to the standard Swing parts (dialogs, lists, tooltips). */
    private static void installTheme() {
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
    }

    /** The first good-looking font that is installed (Segoe UI on Windows, Helvetica Neue on macOS). */
    private static String pickFont() {
        java.util.List<String> installed = Arrays.asList(GraphicsEnvironment.getLocalGraphicsEnvironment().getAvailableFontFamilyNames());
        for (String f : new String[]{"Segoe UI", "Helvetica Neue", "Inter", "Noto Sans", "DejaVu Sans"}) {
            if (installed.contains(f)) return f;
        }
        return Font.SANS_SERIF;
    }

    /** Bold small text with a little letter spacing, for labels such as "PENDING SESSIONS". */
    private static Font caps(int size) {
        Map<java.awt.font.TextAttribute, Object> a = new HashMap<>();
        a.put(java.awt.font.TextAttribute.TRACKING, 0.06);
        return new Font(FONT, Font.BOLD, size).deriveFont(a);
    }

    /** Turns on anti-aliasing (and the desktop's text settings) for custom painting. */
    private static void smooth(Graphics2D g) {
        g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
        Object hints = Toolkit.getDefaultToolkit().getDesktopProperty("awt.font.desktophints");
        if (hints instanceof Map) g.addRenderingHints((Map<?, ?>) hints);
        else g.setRenderingHint(RenderingHints.KEY_TEXT_ANTIALIASING, RenderingHints.VALUE_TEXT_ANTIALIAS_ON);
    }

    private static JLabel label(String text, int style, int size, Color color) {
        JLabel l = new JLabel(text);
        l.setFont(new Font(FONT, style, size));
        l.setForeground(color);
        return l;
    }

    /** A muted label registered under a key, so refresh() can update its text. */
    private JLabel note(String key) {
        JLabel l = label(" ", Font.PLAIN, 12, MUTED);
        l.setBorder(new EmptyBorder(0, 8, 0, 4));   // a little room so the text is never clipped at the edge
        notes.put(key, l);
        return l;
    }

    private void setNote(String key, String text) {
        JLabel l = notes.get(key);
        if (l != null) {
            l.setText(text.isEmpty() ? " " : text);
            l.setToolTipText(text.isEmpty() ? null : text);   // full text on hover if a small window cuts it off
        }
    }

    /** Makes a component span the full width of a vertical BoxLayout and line up on the left. */
    private static <T extends JComponent> T fullWidth(T c) {
        c.setAlignmentX(Component.LEFT_ALIGNMENT);
        c.setMaximumSize(new Dimension(Integer.MAX_VALUE, c.getPreferredSize().height));
        return c;
    }

    private static JPanel page() {
        JPanel p = new JPanel(new BorderLayout(16, 18));
        p.setOpaque(false);
        p.setBorder(new EmptyBorder(22, 28, 24, 28));
        return p;
    }

    /** Stacks the toolbar (and an optional hint) at the top of a page. */
    private static JPanel top(JComponent... parts) {
        JPanel p = new JPanel();
        p.setLayout(new BoxLayout(p, BoxLayout.Y_AXIS));
        p.setOpaque(false);
        for (int i = 0; i < parts.length; i++) {
            if (i > 0) p.add(Box.createVerticalStrut(14));
            p.add(fullWidth(parts[i]));
        }
        return p;
    }

    /** Buttons on the left and a short note on the right (for example "4 courses"). */
    private JPanel toolbar(String noteKey, JButton... buttons) {
        JPanel left = new JPanel(new FlowLayout(FlowLayout.LEFT, 0, 0));
        left.setOpaque(false);
        for (JButton b : buttons) {
            left.add(b);
            left.add(Box.createHorizontalStrut(10));
        }
        JPanel bar = new JPanel(new BorderLayout());
        bar.setOpaque(false);
        bar.add(left, BorderLayout.WEST);
        JLabel note = note(noteKey);
        note.setFont(F_TEXT);
        bar.add(note, BorderLayout.EAST);
        return bar;
    }

    /** A soft blue line that explains how a page works. */
    private static JComponent hint(String text) {
        JLabel l = pill(text, icon("info", 16, BLUE), BLUE_SOFT, BLUE_INK);
        l.setFont(F_TEXT);
        l.setIconTextGap(10);
        l.setBorder(new EmptyBorder(10, 14, 10, 14));
        l.putClientProperty("radius", 12);
        return l;
    }

    /** A white rounded card with a soft shadow. */
    private static JPanel card(LayoutManager lm) {
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
    private static JPanel section(String heading, JComponent aside, Component body) {
        JPanel top = new JPanel(new BorderLayout());
        top.setOpaque(false);
        top.add(label(heading, Font.BOLD, 15, INK), BorderLayout.WEST);
        if (aside != null) top.add(aside, BorderLayout.EAST);
        JPanel c = card(new BorderLayout(0, 10));
        c.add(top, BorderLayout.NORTH);
        c.add(body, BorderLayout.CENTER);
        return c;
    }

    /** A borderless scroll pane with a thin, rounded scroll bar. */
    private static JScrollPane scroll(final Component view) {
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
        sp.getViewport().addComponentListener(new java.awt.event.ComponentAdapter() {
            public void componentResized(java.awt.event.ComponentEvent e) {
                SwingUtilities.invokeLater(view::revalidate);   // lets wrapped text measure the new width
            }
        });
        return sp;
    }

    /** A scroll pane for a vertical list: the list keeps its natural height and never scrolls sideways. */
    private static JScrollPane scrollList(JPanel list) {
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
                if (r.isEmpty() || !scrollbar.isEnabled()) return;
                Graphics2D g2 = (Graphics2D) g.create();
                smooth(g2);
                g2.setColor(isThumbRollover() || isDragging ? new Color(160, 170, 190) : thumbColor);
                g2.fillRoundRect(r.x + 2, r.y + 2, r.width - 4, r.height - 4, 6, 6);
                g2.dispose();
            }
        };
    }

    /** An empty vertical list; rows are added by updateDashboard() and renderReport(). */
    private static JPanel stack() {
        JPanel l = new JPanel();
        l.setLayout(new BoxLayout(l, BoxLayout.Y_AXIS));
        l.setOpaque(false);
        return l;
    }

    /** One list entry: something on the left (a tag, a date or an icon) and a title with a muted detail line. */
    private static JPanel listRow(JComponent lead, int leadWidth, String title, String detail) {
        JPanel left = new JPanel(new FlowLayout(FlowLayout.LEFT, 0, 1));
        left.setOpaque(false);
        left.add(lead);
        left.setPreferredSize(new Dimension(leadWidth, lead.getPreferredSize().height + 2));
        JPanel text = new JPanel();
        text.setLayout(new BoxLayout(text, BoxLayout.Y_AXIS));
        text.setOpaque(false);
        text.add(wrapText(title, F_BOLD, INK));
        if (detail != null) {
            text.add(Box.createVerticalStrut(3));
            text.add(wrapText(detail, F_SMALL, MUTED));
        }
        JPanel row = new JPanel(new BorderLayout(12, 0));
        row.setOpaque(false);
        row.setBorder(BorderFactory.createCompoundBorder(
                BorderFactory.createMatteBorder(0, 0, 1, 0, ROW_LINE), new EmptyBorder(10, 0, 10, 6)));
        row.add(left, BorderLayout.WEST);
        row.add(text, BorderLayout.CENTER);
        return row;
    }

    private static JPanel emptyRow(String iconKind, Color fg, Color bg, String title, String detail) {
        JPanel row = listRow(new JLabel(badge(iconKind, 36, fg, bg)), 48, title, detail);
        row.setBorder(new EmptyBorder(10, 0, 10, 0));
        return row;
    }

    /** A conflict from PlannerService.conflicts() shown with a coloured tag and a plain-language explanation. */
    private static JPanel warningRow(String w) {
        int colon = w.indexOf(": ");
        String kind = colon > 0 ? w.substring(0, colon) : "", text = colon > 0 ? w.substring(colon + 2) : w;
        if (kind.startsWith("Exam")) {
            return listRow(chip("EXAMS", RED_SOFT, RED), 86, text, "Two exams are on the same day.");
        } else if (kind.startsWith("Class")) {
            return listRow(chip("CLASS", AMBER_SOFT, AMBER), 86, text, "A study session overlaps one of your classes.");
        } else if (kind.startsWith("Overloaded")) {
            return listRow(chip("BUSY DAY", VIOLET_SOFT, VIOLET), 86, text,
                    "More than " + PlannerService.MAX_SESSIONS_PER_DAY + " pending study sessions on one day.");
        }
        return listRow(chip("NOTE", BLUE_SOFT, BLUE), 86, w, null);
    }

    /** A course in the report: code, name, counts and a bar for the combined listed weight. */
    private static JPanel workloadRow(Course c, int tasks, double weight, int sessionsLeft) {
        JLabel code = label(c.code, Font.BOLD, 13, BLUE);
        code.setVerticalAlignment(SwingConstants.TOP);
        code.setPreferredSize(new Dimension(76, code.getPreferredSize().height));
        String who = c.instructor == null || c.instructor.isEmpty() ? "" : "  \u00b7  " + c.instructor;
        JPanel text = new JPanel();
        text.setLayout(new BoxLayout(text, BoxLayout.Y_AXIS));
        text.setOpaque(false);
        text.add(wrapText(c.name, F_BOLD, INK));
        text.add(Box.createVerticalStrut(3));
        text.add(wrapText(plural(tasks, "assessment", "assessments") + "  \u00b7  "
                + plural(sessionsLeft, "study session left", "study sessions left") + who, F_SMALL, MUTED));
        JComponent meter = bar(weight > 100 ? RED : BLUE);
        meter.putClientProperty("value", Math.min(weight, 100) / 100.0);
        meter.setPreferredSize(new Dimension(150, 8));
        JPanel meterBox = new JPanel(new GridBagLayout());
        meterBox.setOpaque(false);
        meterBox.add(meter);
        JLabel pct = label(weightText(weight), Font.BOLD, 13, INK);
        pct.setHorizontalAlignment(SwingConstants.RIGHT);
        pct.setPreferredSize(new Dimension(52, pct.getPreferredSize().height));
        JPanel right = new JPanel(new BorderLayout(10, 0));
        right.setOpaque(false);
        right.add(meterBox, BorderLayout.CENTER);
        right.add(pct, BorderLayout.EAST);
        JPanel row = new JPanel(new BorderLayout(14, 0));
        row.setOpaque(false);
        row.setBorder(BorderFactory.createCompoundBorder(
                BorderFactory.createMatteBorder(0, 0, 1, 0, ROW_LINE), new EmptyBorder(12, 0, 12, 4)));
        row.add(code, BorderLayout.WEST);
        row.add(text, BorderLayout.CENTER);
        row.add(right, BorderLayout.EAST);
        return row;
    }

    /** A big number with a small caption, used in the weekly summary. */
    private static JPanel metric(String caption, int value, Color color) {
        JPanel m = new JPanel();
        m.setLayout(new BoxLayout(m, BoxLayout.Y_AXIS));
        m.setOpaque(false);
        JLabel c = new JLabel(caption.toUpperCase(Locale.ENGLISH));
        c.setFont(F_CAPS);
        c.setForeground(MUTED);
        m.add(fullWidth(label(String.valueOf(value), Font.BOLD, 28, color)));
        m.add(fullWidth(c));
        return m;
    }

    /** A small calendar block such as "MON 9"; today's block is blue. */
    private static JComponent dateBlock(LocalDate d) {
        final boolean today = d.equals(LocalDate.now());
        JPanel b = new JPanel(new BorderLayout()) {
            protected void paintComponent(Graphics g) {
                Graphics2D g2 = (Graphics2D) g.create();
                smooth(g2);
                g2.setColor(today ? BLUE : new Color(241, 244, 250));
                g2.fillRoundRect(0, 0, getWidth() - 1, getHeight() - 1, 12, 12);
                g2.dispose();
            }
        };
        b.setOpaque(false);
        b.setBorder(new EmptyBorder(5, 0, 6, 0));
        JLabel dow = label(d.getDayOfWeek().getDisplayName(java.time.format.TextStyle.SHORT, Locale.ENGLISH).toUpperCase(Locale.ENGLISH),
                Font.BOLD, 10, today ? new Color(214, 226, 255) : MUTED);
        JLabel num = label(String.valueOf(d.getDayOfMonth()), Font.BOLD, 17, today ? Color.WHITE : INK);
        dow.setHorizontalAlignment(SwingConstants.CENTER);
        num.setHorizontalAlignment(SwingConstants.CENTER);
        b.add(dow, BorderLayout.NORTH);
        b.add(num, BorderLayout.CENTER);
        b.setPreferredSize(new Dimension(44, 50));
        return b;
    }

    /** Read-only text that wraps to the width it is given (a JLabel cannot wrap). */
    private static JTextArea wrapText(String text, Font font, Color color) {
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

    /** Heading plus wrapped message, used by warn() and confirm(). */
    private static JPanel messageBody(String heading, String message) {
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

    /** Lays out again now, and once more after wrapped text has measured its real width. */
    private static void relayout(final JComponent... cs) {
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

    /** Common settings for buttons that paint their own background. */
    private static void flat(AbstractButton b) {
        b.setContentAreaFilled(false);
        b.setBorderPainted(false);
        b.setFocusPainted(false);
        b.setOpaque(false);
        b.setRolloverEnabled(true);
        b.setCursor(HAND);
    }

    /** A rounded button. kind: "primary" (blue), "success" (green), "danger" (red), "soft-danger" or "plain". */
    private static JButton button(String text, String iconKind, String kind) {
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
                g2.setColor(m.isPressed() ? shade(bg, 0.86f) : m.isRollover() ? shade(bg, 0.94f) : bg);
                g2.fillRoundRect(0, 0, getWidth() - 1, getHeight() - 1, 12, 12);
                if (edge != null) {
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
            b.setIconTextGap(8);
        }
        b.setForeground(fg);
        b.setFont(F_BOLD);
        flat(b);
        b.setBorder(new EmptyBorder(9, iconKind == null ? 20 : 15, 9, 20));
        b.addFocusListener(new java.awt.event.FocusAdapter() {
            public void focusGained(java.awt.event.FocusEvent e) {
                e.getComponent().repaint();
            }

            public void focusLost(java.awt.event.FocusEvent e) {
                e.getComponent().repaint();
            }
        });
        return b;
    }

    private static Color shade(Color c, float f) {
        return new Color(Math.round(c.getRed() * f), Math.round(c.getGreen() * f), Math.round(c.getBlue() * f));
    }

    /** A label on a rounded coloured background (date in the header, page hints, toasts). */
    private static JLabel pill(String text, Icon icon, final Color bg, Color fg) {
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
    private static JLabel chip(String text, Color bg, Color fg) {
        JLabel l = pill(text, null, bg, fg);
        l.setFont(caps(10));
        l.setBorder(new EmptyBorder(4, 9, 4, 9));
        return l;
    }

    /** A thin rounded progress bar; the fraction (0..1) is read from the "value" client property. */
    private static JComponent bar(final Color color) {
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
                    g2.setColor(color);
                    g2.fillRoundRect(0, 0, Math.max(h, (int) Math.round(getWidth() * f)), h, h, h);
                }
                g2.dispose();
            }
        };
        b.setPreferredSize(new Dimension(80, 6));
        return b;
    }

    /** A text field with a light placeholder that is shown while it is empty. */
    private static JTextField field(String text, final String placeholder) {
        return new JTextField(text) {
            protected void paintComponent(Graphics g) {
                super.paintComponent(g);
                if (getText().isEmpty()) {
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
    }

    /** Rounded border for form fields; it turns blue while the field has the keyboard focus. */
    private static javax.swing.border.Border fieldBorder(final Insets pad) {
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

    /** Gives text fields and drop-downs in the forms the same rounded look and a blue focus border. */
    private static void styleField(final JComponent f) {
        f.setFont(F_TEXT);
        f.setForeground(INK);
        if (f instanceof JTextField) {
            JTextField t = (JTextField) f;
            t.setBorder(fieldBorder(new Insets(9, 12, 9, 12)));
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
                    // the white dialog shows through; focus is shown by the blue border
                }

                public void paintCurrentValue(Graphics g, Rectangle bounds, boolean hasFocus) {
                    super.paintCurrentValue(g, bounds, false);
                }
            });
            cb.setRenderer(new DefaultListCellRenderer() {
                public Component getListCellRendererComponent(JList<?> list, Object value, int index, boolean sel, boolean focus) {
                    super.getListCellRendererComponent(list, value, index, sel, false);
                    setBorder(new EmptyBorder(7, 10, 7, 10));
                    return this;
                }
            });
            cb.setBackground(Color.WHITE);
            cb.setOpaque(false);
            cb.setBorder(fieldBorder(new Insets(2, 2, 2, 2)));
            cb.setCursor(HAND);
        }
        f.addFocusListener(new java.awt.event.FocusAdapter() {
            public void focusGained(java.awt.event.FocusEvent e) {
                f.repaint();
            }

            public void focusLost(java.awt.event.FocusEvent e) {
                f.repaint();
            }
        });
    }

    private DefaultTableModel model(String... cols) {
        return new DefaultTableModel(cols, 0) {
            public boolean isCellEditable(int r, int c) {
                return false;
            }
        };
    }

    /** A clean table: no grid, roomy rows, hover highlight, styled header and a friendly message when empty. */
    private static JTable table(DefaultTableModel m, final String iconKind, final String emptyText) {
        final JTable t = new JTable(m) {
            protected void paintComponent(Graphics g) {
                super.paintComponent(g);
                if (getRowCount() == 0) {
                    Graphics2D g2 = (Graphics2D) g.create();
                    smooth(g2);
                    Rectangle r = getVisibleRect();
                    int cy = r.y + r.height / 2 - 40;
                    badge(iconKind, 56, BLUE, BLUE_SOFT).paintIcon(this, g2, r.x + (r.width - 56) / 2, cy - 28);
                    g2.setFont(new Font(FONT, Font.BOLD, 15));
                    g2.setColor(INK);
                    centre(g2, "Nothing here yet", r, cy + 54);
                    g2.setFont(F_TEXT);
                    g2.setColor(MUTED);
                    centre(g2, emptyText, r, cy + 78);
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
        t.getTableHeader().setReorderingAllowed(false);
        t.getTableHeader().setBackground(Color.WHITE);   // hides hairline seams at 125 % / 150 % display scaling
        t.getTableHeader().setDefaultRenderer(new javax.swing.table.TableCellRenderer() {
            private final JLabel l = new JLabel();

            {
                l.setOpaque(true);
                l.setBackground(Color.WHITE);
                l.setForeground(MUTED);
                l.setFont(F_CAPS);
                l.setBorder(BorderFactory.createCompoundBorder(
                        BorderFactory.createMatteBorder(0, 0, 1, 0, LINE), new EmptyBorder(12, 14, 11, 14)));
            }

            public Component getTableCellRendererComponent(JTable tb, Object v, boolean s, boolean f, int r, int c) {
                l.setText(String.valueOf(v).toUpperCase(Locale.ENGLISH));
                return l;
            }
        });
        t.addMouseMotionListener(new java.awt.event.MouseMotionAdapter() {   // highlight the row under the mouse
            public void mouseMoved(java.awt.event.MouseEvent e) {
                Integer row = t.rowAtPoint(e.getPoint());
                if (!row.equals(t.getClientProperty("hover"))) {
                    t.putClientProperty("hover", row);
                    t.repaint();
                }
            }
        });
        t.addMouseListener(new java.awt.event.MouseAdapter() {
            public void mouseExited(java.awt.event.MouseEvent e) {
                t.putClientProperty("hover", -1);
                t.repaint();
            }
        });
        return t;
    }

    private static void centre(Graphics2D g, String s, Rectangle r, int baseline) {
        g.drawString(s, r.x + (r.width - g.getFontMetrics().stringWidth(s)) / 2, baseline);
    }

    private static void widths(JTable t, int... w) {
        for (int i = 0; i < w.length; i++) t.getColumnModel().getColumn(i).setPreferredWidth(w[i]);
    }

    /** The Delete key runs the same action as the "Delete Selected" button. */
    private static void onDelete(JTable t, final Runnable action) {
        t.getInputMap(JComponent.WHEN_FOCUSED).put(KeyStroke.getKeyStroke("DELETE"), "deleteRow");
        t.getActionMap().put("deleteRow", new AbstractAction() {
            public void actionPerformed(java.awt.event.ActionEvent e) {
                action.run();
            }
        });
    }

    /** A rounded white card around a table. */
    private static JPanel tableCard(JTable t) {
        JScrollPane sp = scroll(t);
        sp.setColumnHeaderView(t.getTableHeader());
        sp.getColumnHeader().setBackground(Color.WHITE);
        JPanel corner = new JPanel();
        corner.setBackground(Color.WHITE);
        sp.setCorner(JScrollPane.UPPER_RIGHT_CORNER, corner);
        JPanel c = card(new BorderLayout());
        c.setBorder(new EmptyBorder(8, 10, 16, 10));
        c.add(sp, BorderLayout.CENTER);
        return c;
    }

    /** Formats table cells and draws status, type and difficulty as coloured tags. The table models keep the raw values. */
    private static javax.swing.table.TableCellRenderer cells() {
        return new javax.swing.table.DefaultTableCellRenderer() {
            private final javax.swing.border.Border pad = BorderFactory.createCompoundBorder(
                    BorderFactory.createMatteBorder(0, 0, 1, 0, ROW_LINE), new EmptyBorder(0, 14, 0, 14));
            private Color tagBg, tagFg;   // set when the cell is drawn as a tag

            public Component getTableCellRendererComponent(JTable t, Object v, boolean sel, boolean focus, int row, int col) {
                String column = t.getColumnName(col);
                super.getTableCellRendererComponent(t, cellText(column, v), sel, false, row, col);
                setBackground(sel ? t.getSelectionBackground() : Integer.valueOf(row).equals(t.getClientProperty("hover")) ? HOVER : Color.WHITE);
                setForeground(INK);
                setFont(F_TEXT);
                setBorder(pad);
                tagBg = null;
                switch (column) {
                    case "Code":
                    case "Course":
                        setFont(F_BOLD);
                        setForeground(BLUE);
                        break;
                    case "Title":
                    case "Course Name":
                    case "Assessment":
                    case "Day":
                        setFont(F_BOLD);
                        break;
                    case "Instructor":
                        setForeground(MUTED);
                        break;
                    case "Deadline":
                        if (v instanceof LocalDate) {
                            long d = days((LocalDate) v);
                            if (d < 0) {
                                setForeground(RED);
                                setText(getText() + "  \u00b7  overdue");
                            } else if (d <= 3) {
                                setForeground(AMBER);
                                setText(getText() + "  \u00b7  " + (d == 0 ? "today" : d == 1 ? "tomorrow" : "in " + d + " days"));
                            }
                        }
                        break;
                    case "Date":
                        if (v instanceof LocalDate && days((LocalDate) v) == 0) {
                            setFont(F_BOLD);
                            setForeground(BLUE);
                            setText("Today  \u00b7  " + getText());
                        }
                        break;
                    case "Status": {
                        Object date = t.getModel().getValueAt(row, 0);   // the Date column of the same row
                        boolean missed = "Pending".equals(v) && date instanceof LocalDate && days((LocalDate) date) < 0;
                        if ("Completed".equals(v)) {
                            useTag(GREEN_SOFT, GREEN);
                        } else if (missed) {
                            setText("Missed");
                            useTag(RED_SOFT, RED);
                        } else {
                            useTag(AMBER_SOFT, AMBER);
                        }
                        break;
                    }
                    case "Type":
                        if ("Exam".equals(v)) useTag(VIOLET_SOFT, VIOLET);
                        else if ("Quiz".equals(v)) useTag(AMBER_SOFT, AMBER);
                        else useTag(BLUE_SOFT, BLUE);
                        break;
                    case "Difficulty":
                        if ("Hard".equals(v)) useTag(RED_SOFT, RED);
                        else if ("Medium".equals(v)) useTag(AMBER_SOFT, AMBER);
                        else useTag(GREEN_SOFT, GREEN);
                        break;
                    default:
                        break;
                }
                return this;
            }

            private void useTag(Color bg, Color fg) {
                tagBg = bg;
                tagFg = fg;
            }

            protected void paintComponent(Graphics g) {
                if (tagBg == null) {
                    super.paintComponent(g);
                    return;
                }
                Graphics2D g2 = (Graphics2D) g.create();
                smooth(g2);
                g2.setColor(getBackground());
                g2.fillRect(0, 0, getWidth(), getHeight());
                g2.setFont(new Font(FONT, Font.BOLD, 12));
                FontMetrics fm = g2.getFontMetrics();
                int h = 24, w = fm.stringWidth(getText()) + 32, x = getInsets().left, y = (getHeight() - 1 - h) / 2;
                g2.setColor(tagBg);
                g2.fillRoundRect(x, y, w, h, h, h);
                g2.setColor(tagFg);
                g2.fillOval(x + 10, y + h / 2 - 3, 6, 6);
                g2.drawString(getText(), x + 22, y + (h - fm.getHeight()) / 2 + fm.getAscent());
                g2.dispose();
            }
        };
    }

    /** The text shown in a table cell. */
    private static String cellText(String column, Object v) {
        if (v == null) return "";
        if (v instanceof LocalDate) return DAY.format((LocalDate) v);
        if (v instanceof Double) return weightText((Double) v);
        if (column.equals("Time")) return String.valueOf(v).replace("-", "  \u2013  ");
        if (column.equals("Day")) return cap(String.valueOf(v));
        if (column.equals("Instructor") && String.valueOf(v).isEmpty()) return "\u2014";
        return String.valueOf(v);
    }

    private static String weightText(double w) {
        return (w == Math.rint(w) ? String.valueOf((long) w) : String.format(Locale.ENGLISH, "%.1f", w)) + "%";
    }

    private static String cap(String s) {
        return s.isEmpty() ? s : s.charAt(0) + s.substring(1).toLowerCase(Locale.ENGLISH);
    }

    private static String plural(int n, String one, String many) {
        return n + " " + (n == 1 ? one : many);
    }

    /** Days from today to the given date (negative when it is in the past). */
    private static long days(LocalDate d) {
        return d.toEpochDay() - LocalDate.now().toEpochDay();
    }

    /** Small line icons drawn on a 20 x 20 grid with Java 2D, so no image files are needed. */
    private static Icon icon(final String kind, final int size, final Color color) {
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
                    case "Class Times":          // clock
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
                    case "copy":
                        g2.drawRoundRect(7, 6, 10, 12, 3, 3);
                        g2.drawPolyline(new int[]{4, 4, 12}, new int[]{14, 3, 3}, 3);
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

    /** An icon on a soft coloured tile (dashboard cards, dialogs and empty states). */
    private static Icon badge(final String kind, final int size, final Color fg, final Color bg) {
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

    /** A four-point star used by the "Generate Study Plan" icon. */
    private static Shape star(double cx, double cy, double r, double inner) {
        java.awt.geom.Path2D.Double p = new java.awt.geom.Path2D.Double();
        for (int i = 0; i < 8; i++) {
            double angle = Math.PI / 4 * i - Math.PI / 2, radius = i % 2 == 0 ? r : inner;
            double px = cx + Math.cos(angle) * radius, py = cy + Math.sin(angle) * radius;
            if (i == 0) p.moveTo(px, py);
            else p.lineTo(px, py);
        }
        p.closePath();
        return p;
    }
}
