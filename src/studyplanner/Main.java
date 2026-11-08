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
        try (ObjectInputStream in = new ObjectInputStream(new FileInputStream(file))) {
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

/** The main window: a sidebar with six pages (Dashboard, Courses, Assessments, Schedule, Class Times, Reports). */
class PlannerFrame extends JFrame {
    private final Color NAVY = new Color(26, 37, 68), BLUE = new Color(55, 105, 210), BG = new Color(244, 247, 252);
    private PlannerData data;
    private final DataStore store = new DataStore();
    private final PlannerService service;
    private final CardLayout layout = new CardLayout();
    private final JPanel pages = new JPanel(layout);
    private DefaultTableModel cm, am, sm, tm;   // table models: courses, assessments, sessions, class times
    private JTable ct, at, st, tt;
    private JTextArea upcoming, conflictBox, reportBox;
    private final JLabel title;

    PlannerFrame() {
        data = store.load();
        service = new PlannerService(data);
        setTitle("Study Planner and Exam Scheduler");
        setSize(1120, 720);
        setMinimumSize(new Dimension(950, 620));
        setDefaultCloseOperation(EXIT_ON_CLOSE);
        setLocationRelativeTo(null);
        setLayout(new BorderLayout());
        add(sidebar(), BorderLayout.WEST);

        JPanel right = new JPanel(new BorderLayout());
        right.setBackground(BG);
        JPanel header = new JPanel(new BorderLayout());
        header.setBackground(Color.WHITE);
        header.setBorder(new EmptyBorder(18, 25, 18, 25));
        title = new JLabel("Dashboard");
        title.setFont(new Font("SansSerif", Font.BOLD, 23));
        title.setForeground(NAVY);
        header.add(title, BorderLayout.WEST);
        JLabel brand = new JLabel("STUDENT WORKSPACE");
        brand.setForeground(Color.GRAY);
        header.add(brand, BorderLayout.EAST);
        right.add(header, BorderLayout.NORTH);

        pages.setBackground(BG);
        pages.add(dashboard(), "Dashboard");
        pages.add(courses(), "Courses");
        pages.add(assessments(), "Assessments");
        pages.add(schedule(), "Schedule");
        pages.add(classTimes(), "Class Times");
        pages.add(reports(), "Reports");
        right.add(pages, BorderLayout.CENTER);
        add(right, BorderLayout.CENTER);
        refresh();

        if (store.loadWarning != null) {   // tell the user if the data file was damaged
            SwingUtilities.invokeLater(() -> warn(store.loadWarning));
        }
    }

    // ---------------------------------------------------------------- building the screens

    private JPanel sidebar() {
        JPanel p = new JPanel();
        p.setPreferredSize(new Dimension(220, 0));
        p.setBackground(NAVY);
        p.setLayout(new BoxLayout(p, BoxLayout.Y_AXIS));
        p.setBorder(new EmptyBorder(25, 15, 20, 15));
        JLabel logo = new JLabel("STUDY PLANNER");
        logo.setForeground(Color.WHITE);
        logo.setFont(new Font("SansSerif", Font.BOLD, 19));
        logo.setBorder(new EmptyBorder(0, 8, 28, 0));
        p.add(logo);
        for (String n : new String[]{"Dashboard", "Courses", "Assessments", "Schedule", "Class Times", "Reports"}) {
            JButton b = new JButton(n);
            b.setMaximumSize(new Dimension(190, 43));
            b.setHorizontalAlignment(SwingConstants.LEFT);
            b.setBackground(NAVY);
            b.setForeground(Color.WHITE);
            b.setFocusPainted(false);
            b.setBorderPainted(false);
            b.addActionListener(e -> {
                layout.show(pages, n);
                title.setText(n);
                refresh();
            });
            p.add(b);
            p.add(Box.createVerticalStrut(7));
        }
        p.add(Box.createVerticalGlue());
        JLabel foot = new JLabel("OOAD \u2022 FALL 2026");
        foot.setForeground(new Color(175, 190, 215));
        p.add(foot);
        return p;
    }

    private JPanel page() {
        JPanel p = new JPanel(new BorderLayout(12, 12));
        p.setBackground(BG);
        p.setBorder(new EmptyBorder(22, 24, 22, 24));
        return p;
    }

    private JButton action(String s) {
        JButton b = new JButton(s);
        b.setBackground(BLUE);
        b.setForeground(Color.WHITE);
        b.setFocusPainted(false);
        b.setBorder(new EmptyBorder(10, 14, 10, 14));
        return b;
    }

    private JPanel panel(Component c) {
        JPanel p = new JPanel(new BorderLayout());
        p.setBackground(Color.WHITE);
        p.setBorder(BorderFactory.createCompoundBorder(
                BorderFactory.createLineBorder(new Color(225, 230, 240)), new EmptyBorder(12, 12, 12, 12)));
        p.add(new JScrollPane(c));
        return p;
    }

    private JTable table(DefaultTableModel m) {
        JTable t = new JTable(m);
        t.setRowHeight(29);
        t.getTableHeader().setFont(new Font("SansSerif", Font.BOLD, 12));
        return t;
    }

    private DefaultTableModel model(String... cols) {
        return new DefaultTableModel(cols, 0) {
            public boolean isCellEditable(int r, int c) {
                return false;
            }
        };
    }

    private JPanel buttonRow(JButton... buttons) {
        JPanel actions = new JPanel(new FlowLayout(FlowLayout.LEFT));
        actions.setOpaque(false);
        for (JButton b : buttons) actions.add(b);
        return actions;
    }

    private JPanel dashboard() {
        JPanel p = page();
        JPanel stats = new JPanel(new GridLayout(1, 4, 12, 12));
        stats.setOpaque(false);
        for (String s : new String[]{"Courses", "Assessments", "Pending Sessions", "Completed Sessions"}) {
            JPanel c = new JPanel(new BorderLayout());
            c.setBackground(Color.WHITE);
            c.setBorder(new EmptyBorder(15, 15, 15, 15));
            JLabel a = new JLabel(s.toUpperCase());
            a.setForeground(Color.GRAY);
            JLabel v = new JLabel("0");
            v.setName("stat:" + s);                       // found again by setStat()
            v.setFont(new Font("SansSerif", Font.BOLD, 27));
            v.setForeground(NAVY);
            c.add(a, BorderLayout.NORTH);
            c.add(v, BorderLayout.CENTER);
            stats.add(c);
        }
        JPanel lower = new JPanel(new GridLayout(1, 2, 12, 12));
        lower.setOpaque(false);
        upcoming = new JTextArea();
        upcoming.setEditable(false);
        upcoming.setFont(new Font("SansSerif", Font.PLAIN, 13));
        conflictBox = new JTextArea();
        conflictBox.setEditable(false);
        conflictBox.setFont(new Font("SansSerif", Font.PLAIN, 13));
        lower.add(panel(upcoming));
        lower.add(panel(conflictBox));
        p.add(stats, BorderLayout.NORTH);
        p.add(lower, BorderLayout.CENTER);
        return p;
    }

    private JPanel courses() {
        JPanel p = page();
        cm = model("Code", "Course Name", "Instructor");
        ct = table(cm);
        JButton add = action("Add Course"), del = action("Delete Selected");
        add.addActionListener(e -> addCourse());
        del.addActionListener(e -> deleteCourse());
        p.add(buttonRow(add, del), BorderLayout.NORTH);
        p.add(panel(ct), BorderLayout.CENTER);
        return p;
    }

    private JPanel assessments() {
        JPanel p = page();
        am = model("Title", "Course", "Type", "Deadline", "Weight %", "Difficulty");
        at = table(am);
        JButton add = action("Add Assessment"), del = action("Delete Selected");
        add.addActionListener(e -> addAssessment());
        del.addActionListener(e -> deleteAssessment());
        p.add(buttonRow(add, del), BorderLayout.NORTH);
        p.add(panel(at), BorderLayout.CENTER);
        return p;
    }

    private JPanel schedule() {
        JPanel p = page();
        sm = model("Date", "Time", "Course", "Assessment", "Status");
        st = table(sm);
        JButton gen = action("Generate Study Plan"), done = action("Mark Complete");
        gen.addActionListener(e -> generate());
        done.addActionListener(e -> complete());
        p.add(buttonRow(gen, done), BorderLayout.NORTH);
        p.add(panel(st), BorderLayout.CENTER);
        return p;
    }

    private JPanel classTimes() {
        JPanel p = page();
        tm = model("Course", "Day", "Start", "End");
        tt = table(tm);
        JButton add = action("Add Class Time"), del = action("Delete Selected");
        add.addActionListener(e -> addMeeting());
        del.addActionListener(e -> {
            int r = tt.getSelectedRow();
            if (r >= 0) {
                data.meetings.remove(r);
                save();
                refresh();
            }
        });
        p.add(buttonRow(add, del), BorderLayout.NORTH);
        p.add(panel(tt), BorderLayout.CENTER);
        return p;
    }

    private JPanel reports() {
        JPanel p = page();
        reportBox = new JTextArea();
        reportBox.setEditable(false);
        reportBox.setFont(new Font("Monospaced", Font.PLAIN, 13));
        JButton make = action("Generate Reports");
        make.addActionListener(e -> reportBox.setText(report()));
        p.add(make, BorderLayout.NORTH);
        p.add(panel(reportBox), BorderLayout.CENTER);
        return p;
    }

    // ---------------------------------------------------------------- user actions

    private void addCourse() {
        JTextField code = new JTextField(), name = new JTextField(), inst = new JTextField();
        Object[] f = {"Course code", code, "Course name", name, "Instructor", inst};
        if (JOptionPane.showConfirmDialog(this, f, "Add Course", JOptionPane.OK_CANCEL_OPTION) == 0) {
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
        data.courses.remove(r);
        data.meetings.removeIf(m -> m.course.equals(code));   // no orphan class times
        save();
        refresh();
    }

    private void addAssessment() {
        if (data.courses.isEmpty()) {
            warn("Add a course first.");
            return;
        }
        JComboBox<String> course = new JComboBox<>();
        for (Course c : data.courses) course.addItem(c.code + " - " + c.name);
        JTextField name = new JTextField(), date = new JTextField(LocalDate.now().plusDays(7).toString()), weight = new JTextField("10");
        JComboBox<String> type = new JComboBox<>(new String[]{"Assignment", "Quiz", "Exam"});
        JComboBox<String> diff = new JComboBox<>(new String[]{"Easy", "Medium", "Hard"});
        Object[] f = {"Course", course, "Title", name, "Type", type, "Deadline (YYYY-MM-DD)", date, "Weight (0-100)", weight, "Difficulty", diff};
        if (JOptionPane.showConfirmDialog(this, f, "Add Assessment", JOptionPane.OK_CANCEL_OPTION) == 0) {
            try {
                String cc = ((String) course.getSelectedItem()).split(" - ")[0];
                LocalDate dl = LocalDate.parse(date.getText().trim());
                double w = Double.parseDouble(weight.getText().trim());
                if (name.getText().trim().isEmpty() || w < 0 || w > 100) throw new Exception();
                data.assessments.add(new Assessment(UUID.randomUUID().toString(), cc, name.getText().trim(),
                        (String) type.getSelectedItem(), dl, w, (String) diff.getSelectedItem()));
                save();
                refresh();
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
        Assessment target = data.assessments.remove(r);
        data.sessions.removeIf(s -> s.assessmentId.equals(target.id));
        save();
        refresh();
    }

    private void addMeeting() {
        if (data.courses.isEmpty()) {
            warn("Add a course first.");
            return;
        }
        JComboBox<String> course = new JComboBox<>();
        for (Course c : data.courses) course.addItem(c.code);
        JComboBox<String> day = new JComboBox<>(new String[]{"MONDAY", "TUESDAY", "WEDNESDAY", "THURSDAY", "FRIDAY", "SATURDAY", "SUNDAY"});
        JTextField start = new JTextField("09:00"), end = new JTextField("10:00");
        Object[] f = {"Course", course, "Day", day, "Start (HH:MM)", start, "End (HH:MM)", end};
        if (JOptionPane.showConfirmDialog(this, f, "Add Class Time", JOptionPane.OK_CANCEL_OPTION) == 0) {
            try {
                LocalTime s = LocalTime.parse(start.getText().trim()), e = LocalTime.parse(end.getText().trim());
                if (!s.isBefore(e)) throw new Exception();
                data.meetings.add(new ClassMeeting((String) course.getSelectedItem(), (String) day.getSelectedItem(), s, e));
                save();
                refresh();
            } catch (Exception ex) {
                warn("Enter valid times; end must be after start.");
            }
        }
    }

    private void generate() {
        int n = 0;
        for (Assessment a : data.assessments) n += service.generate(a);
        save();
        refresh();
        JOptionPane.showMessageDialog(this, n + " new sessions created (17:00-20:00 slots on weekdays before each deadline).");
    }

    private void complete() {
        int r = st.getSelectedRow();
        if (r < 0) {
            warn("Select a session.");
            return;
        }
        data.sessions.get(r).completed = true;   // table rows are in the same order as the list
        save();
        refresh();
    }

    // ---------------------------------------------------------------- refreshing the views

    private void refresh() {
        if (cm != null) {
            cm.setRowCount(0);
            for (Course c : data.courses) cm.addRow(new Object[]{c.code, c.name, c.instructor});
        }
        if (am != null) {
            am.setRowCount(0);
            for (Assessment a : data.assessments) am.addRow(new Object[]{a.title, a.course, a.type, a.deadline, a.weight, a.difficulty});
        }
        if (sm != null) {
            sm.setRowCount(0);
            for (StudySession s : data.sessions) {
                Assessment a = find(s.assessmentId);
                sm.addRow(new Object[]{s.date, s.start + "-" + s.end, s.course, a == null ? "(deleted)" : a.title, s.completed ? "Completed" : "Pending"});
            }
        }
        if (tm != null) {
            tm.setRowCount(0);
            for (ClassMeeting m : data.meetings) tm.addRow(new Object[]{m.course, m.day, m.start, m.end});
        }
        updateDashboard();
    }

    private Assessment find(String id) {
        for (Assessment a : data.assessments) if (a.id.equals(id)) return a;
        return null;
    }

    private void updateDashboard() {
        if (pages.getComponentCount() > 0) {
            JPanel dash = (JPanel) pages.getComponent(0);
            setStat(dash, "Courses", data.courses.size());
            setStat(dash, "Assessments", data.assessments.size());
            setStat(dash, "Pending Sessions", count(false));
            setStat(dash, "Completed Sessions", count(true));
        }
        if (upcoming != null) {
            upcoming.setText("UPCOMING / OVERDUE\n\n");
            for (Assessment a : data.assessments) {
                long days = Duration.between(LocalDate.now().atStartOfDay(), a.deadline.atStartOfDay()).toDays();
                if (days <= 7) {
                    upcoming.append((days < 0 ? "OVERDUE: " : "Due in " + days + " day(s): ") + a.title + " \u2014 " + a.deadline + "\n");
                }
            }
        }
        if (conflictBox != null) {
            conflictBox.setText("SCHEDULING WARNINGS\n\n");
            java.util.List<String> warnings = service.conflicts();
            if (warnings.isEmpty()) conflictBox.append("No conflicts detected.");
            else for (String s : warnings) conflictBox.append("\u2022 " + s + "\n");
        }
    }

    private void setStat(Container c, String key, int val) {
        for (Component x : c.getComponents()) {
            if (x instanceof JLabel && key.equals(x.getName())) ((JLabel) x).setText("" + val);
            if (x instanceof Container) setStat((Container) x, key, val);
        }
    }

    private int count(boolean done) {
        int n = 0;
        for (StudySession s : data.sessions) if (s.completed == done) n++;
        return n;
    }

    /** Builds the text of the weekly summary, the per-course workload and the current warnings. */
    private String report() {
        StringBuilder b = new StringBuilder("STUDY PLANNER REPORT \u2014 " + LocalDate.now() + "\n\nWEEKLY SUMMARY (next 7 days)\n");
        int n = 0, done = 0;
        for (StudySession s : data.sessions) {
            if (!s.date.isBefore(LocalDate.now()) && !s.date.isAfter(LocalDate.now().plusDays(7))) {
                n++;
                if (s.completed) done++;
            }
        }
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
        } catch (IOException e) {
            warn("Could not save: " + e.getMessage());
        }
    }

    private void warn(String s) {
        JOptionPane.showMessageDialog(this, s, "Study Planner", JOptionPane.WARNING_MESSAGE);
    }
}
