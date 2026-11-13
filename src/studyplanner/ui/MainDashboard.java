package studyplanner.ui;

import studyplanner.engine.ScheduleEngine;
import studyplanner.model.AcademicItem;
import studyplanner.model.Assignment;
import studyplanner.model.Course;
import studyplanner.model.Exam;
import studyplanner.model.Quiz;
import studyplanner.model.StudyBlock;
import studyplanner.storage.StorageManager;

import javax.swing.*;
import javax.swing.border.EmptyBorder;
import javax.swing.table.DefaultTableModel;
import java.awt.*;
import java.awt.event.MouseAdapter;
import java.awt.event.MouseEvent;
import java.awt.event.MouseMotionAdapter;
import java.time.LocalDate;
import java.time.LocalTime;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * The main window ("UI: MainDashboard" in the sequence diagrams). As in section 5.4 of the analysis document it is a
 * single window with three tabs: Dashboard &amp; Daily Plan, Courses &amp; Tasks, and Workload Summary.
 *
 * <p>This class only shows data and reacts to the user. Planning and conflict checks are done by
 * {@link ScheduleEngine}; saving and loading by {@link StorageManager}.
 */
@SuppressWarnings("serial")   // Swing windows are never serialized in this program
public final class MainDashboard extends JFrame {
    private static final String DASHBOARD = "Dashboard & Daily Plan", COURSES = "Courses & Tasks", SUMMARY = "Workload Summary";
    private static final String[] TABS = {DASHBOARD, COURSES, SUMMARY};
    private static final String[] TYPES = {"Assignment", "Quiz", "Exam"};
    private static final Integer[] LEVELS = {1, 2, 3, 4, 5};

    private final StorageManager storage;
    private final ScheduleEngine engine;
    private List<String> warnings = new ArrayList<>();

    private final CardLayout cards = new CardLayout();
    private final JPanel pages = new JPanel(cards);
    private final Map<String, JButton> tabButtons = new LinkedHashMap<>();
    private String currentTab = DASHBOARD;
    private JLabel saveState, toastLabel;
    private javax.swing.Timer toastTimer;
    private boolean refreshing;   // true while lists are rebuilt, so selection events are ignored

    // Dashboard & Daily Plan
    private JLabel dashSubtitle, alertNote, todayNote;
    private JComponent todayBar;
    private JPanel alertList;
    private DefaultTableModel blockModel;
    private JTable blockTable;
    private final List<StudyBlock> blockRows = new ArrayList<>();

    // Courses & Tasks
    private final DefaultListModel<Course> courseModel = new DefaultListModel<>();
    private JList<Course> courseList;
    private JButton editCourseButton, deleteCourseButton, editTaskButton, doneTaskButton, deleteTaskButton, addTaskButton;
    private DefaultTableModel taskModel;
    private JTable taskTable;
    private final List<AcademicItem> taskRows = new ArrayList<>();
    private JLabel taskHeading, taskNote, addHeading;
    private JTextField newTitle, newHours, newChapter, newDuration, newWeight;
    private JComboBox<String> newType;
    private JComboBox<Integer> newComplexity;
    private DatePicker newDue;
    private final CardLayout extraCards = new CardLayout();
    private JPanel extraFields;

    // Workload Summary
    private DefaultTableModel summaryModel;
    private JSpinner limitBox;
    private final Map<String, JLabel> stats = new HashMap<>();
    private JComponent completedBar;
    private JLabel limitNote, summaryNote;

    public MainDashboard(StorageManager storage) {
        this.storage = storage;
        Ui.installTheme();
        storage.loadAll();
        engine = new ScheduleEngine(storage);

        setTitle("Study Planner and Exam Scheduler");
        Rectangle screen = GraphicsEnvironment.getLocalGraphicsEnvironment().getMaximumWindowBounds();
        setSize(Math.min(1160, screen.width), Math.min(740, screen.height));
        setMinimumSize(new Dimension(Math.min(980, screen.width), Math.min(640, screen.height)));
        setDefaultCloseOperation(EXIT_ON_CLOSE);
        setLocationRelativeTo(null);

        pages.setOpaque(false);
        pages.add(dashboardTab(), DASHBOARD);
        pages.add(coursesTab(), COURSES);
        pages.add(summaryTab(), SUMMARY);
        JPanel root = new JPanel(new BorderLayout());
        root.setBackground(Ui.BG);
        root.add(topBar(), BorderLayout.NORTH);
        root.add(pages, BorderLayout.CENTER);
        setContentPane(root);

        requestGenerateSchedule(false);   // the plan always starts from today when the app opens
        showTab(DASHBOARD);
        if (storage.getLoadWarning() != null) {
            SwingUtilities.invokeLater(() -> Ui.warn(this, storage.getLoadWarning()));
        }
    }

    // ================================================================== Sequence 1: adding work and planning it

    /**
     * Sequence 1, steps 1 and 2: the student adds an assignment, quiz or exam to a course and it is saved
     * ("saveItem" / "confirmSaved"). FR2: the study blocks are then made automatically (steps 3 to 8).
     */
    private void addAcademicItem(Course course, AcademicItem item) {
        course.addAcademicItem(item);
        if (!storage.saveItem(item)) {
            afterSave(false);
        }
        requestGenerateSchedule(false);
        selectTask(item);
        int planned = 0;
        for (StudyBlock b : item.getBlocks()) {
            if (!b.isDone()) {
                planned++;
            }
        }
        toast(item.getItemCategory() + " added  \u00b7  " + (planned > 0
                ? Ui.plural(planned, "daily study block", "daily study blocks") + " planned"
                : "no study days are left before it"));
    }

    /** Sequence 1, steps 3 to 8: asks the engine to plan again from today, saves the plan and shows it. */
    private void requestGenerateSchedule(boolean announce) {
        ScheduleEngine.ScheduleResult result = engine.generateSchedule(storage.getDailyLimit());
        warnings = new ArrayList<>(result.getWarnings());
        afterSave(storage.updateProgress());
        displayScheduleAndAlerts(result, announce);
    }

    /** Sequence 1, last step: shows the new study blocks and the alerts on every tab. */
    private void displayScheduleAndAlerts(ScheduleEngine.ScheduleResult result, boolean announce) {
        refreshAll();
        if (announce) {
            int n = result.getStudyBlocks().size();
            toast(n == 0 ? "Nothing to plan  \u00b7  add tasks with hours left"
                    : Ui.plural(n, "study block", "study blocks") + " planned"
                    + (warnings.isEmpty() ? "" : "  \u00b7  " + Ui.plural(warnings.size(), "warning", "warnings")));
        }
    }

    // ================================================================== Sequence 2: ticking a block off

    /** Sequence 2: ticks a study block off (or unticks it), which changes the task's remaining hours. */
    private void toggleBlock(int row) {
        if (row < 0 || row >= blockRows.size()) {
            return;
        }
        StudyBlock block = blockRows.get(row);
        boolean saved = block.isDone() ? engine.reopenBlock(block.getBlockId()) : engine.markBlockComplete(block.getBlockId());
        afterSave(saved);
        warnings = new ArrayList<>(engine.detectConflicts(storage.getCoursesAndTasks(), engine.getAllBlocks()));
        refreshAll();
        int again = blockRows.indexOf(block);
        if (again >= 0) {
            blockTable.setRowSelectionInterval(again, again);
        }
        AcademicItem item = block.getParentItem();
        if (!block.isDone()) {
            toast("Block marked as not done  \u00b7  " + Ui.hours(item.getRemainingHours()) + " left for " + item.getTitle());
        } else if (item.isCompleted()) {
            toast("\"" + item.getTitle() + "\" is complete  \u00b7  well done!");
        } else {
            toast(Ui.hours(block.getAllocatedHours()) + " done  \u00b7  " + Ui.hours(item.getRemainingHours()) + " left for " + item.getTitle());
        }
    }

    // ================================================================== courses (UC1)

    private void addCourse() {
        JTextField code = Ui.field("", "e.g. CS302"), name = Ui.field("", "e.g. Object-Oriented Analysis and Design");
        while (Ui.form(this, "Add Course", "Courses hold your assignments, quizzes and exams.", "Courses", "Add Course",
                "Course code", code, "Course title", name)) {
            try {
                Course course = new Course(code.getText(), name.getText());
                storage.addCourse(course);
                afterSave(storage.saveNow());
                refreshAll();
                courseList.setSelectedValue(course, true);
                toast("Course " + course.getCourseCode() + " added  \u00b7  now add its tasks below");
                newTitle.requestFocusInWindow();
                return;
            } catch (IllegalArgumentException ex) {
                Ui.warn(this, ex.getMessage());
            }
        }
    }

    private void editCourse() {
        Course course = courseList.getSelectedValue();
        if (course == null) {
            Ui.warn(this, "Select a course first.");
            return;
        }
        JTextField code = Ui.field(course.getCourseCode(), "e.g. CS302"), name = Ui.field(course.getCourseName(), null);
        while (Ui.form(this, "Edit Course", "Change the course code or title.", "edit", "Save Changes",
                "Course code", code, "Course title", name)) {
            try {
                Course other = storage.findCourse(code.getText());
                if (other != null && other != course) {
                    throw new IllegalArgumentException("Course code " + code.getText().trim() + " already exists.");
                }
                new Course(code.getText(), name.getText());   // checks both values before anything changes
                course.setCourseCode(code.getText());
                course.setCourseName(name.getText());
                afterSave(storage.saveNow());
                refreshAll();
                toast("Course " + course.getCourseCode() + " updated");
                return;
            } catch (IllegalArgumentException ex) {
                Ui.warn(this, ex.getMessage());
            }
        }
    }

    /** Deletes the selected course; its tasks and study blocks go with it (composition). */
    private void deleteCourse() {
        Course course = courseList.getSelectedValue();
        if (course == null) {
            Ui.warn(this, "Select a course first.");
            return;
        }
        int n = course.getItems().size();
        if (!Ui.confirm(this, "Delete course?", course.getCourseCode() + (n > 0 ? " and its " + Ui.plural(n, "task", "tasks")
                + " (with their study blocks)" : "") + " will be deleted.")) {
            return;
        }
        storage.removeCourse(course);
        requestGenerateSchedule(false);
        toast("Course " + course.getCourseCode() + " deleted");
    }

    // ================================================================== academic items (UC2)

    private void addTaskFromInputBar() {
        Course course = courseList.getSelectedValue();
        if (course == null) {
            Ui.warn(this, "Add or select a course first.");
            return;
        }
        try {
            String type = (String) newType.getSelectedItem();
            AcademicItem item;
            if ("Quiz".equals(type)) {
                item = new Quiz(null, newTitle.getText(), newDue.getDate(), number(newHours, "Estimated total hours"),
                        whole(newChapter, "Chapter number"));
            } else if ("Exam".equals(type)) {
                item = new Exam(null, newTitle.getText(), newDue.getDate(), number(newHours, "Estimated total hours"),
                        whole(newDuration, "Exam duration"), number(newWeight, "Exam weight"));
            } else {
                item = new Assignment(null, newTitle.getText(), newDue.getDate(), number(newHours, "Estimated total hours"),
                        (Integer) newComplexity.getSelectedItem());
            }
            addAcademicItem(course, item);
            newTitle.setText("");
            newTitle.requestFocusInWindow();
        } catch (IllegalArgumentException ex) {
            Ui.warn(this, ex.getMessage());
        }
    }

    /** Edits the selected task; FR2: its study blocks are planned again after saving. */
    private void editTask() {
        AcademicItem item = selectedTask();
        if (item == null) {
            Ui.warn(this, "Select a task first.");
            return;
        }
        JTextField title = Ui.field(item.getTitle(), null), hours = Ui.field(plain(item.getEstimatedHours()), null);
        DatePicker due = new DatePicker(item.getDueDate());
        List<Object> fields = new ArrayList<>(Arrays.asList("Task title", title, "Due date", due, "Estimated total hours", hours));
        JComboBox<Integer> complexity = Ui.combo(LEVELS);
        JTextField chapter = Ui.field("", null), duration = Ui.field("", null), weight = Ui.field("", null);
        if (item instanceof Assignment) {
            complexity.setSelectedItem(((Assignment) item).getComplexityLevel());
            fields.addAll(Arrays.asList("Complexity (1-5)", complexity));
        } else if (item instanceof Quiz) {
            chapter.setText(String.valueOf(((Quiz) item).getChapterNumber()));
            fields.addAll(Arrays.asList("Chapter number", chapter));
        } else if (item instanceof Exam) {
            duration.setText(String.valueOf(((Exam) item).getExamDurationMinutes()));
            weight.setText(plain(((Exam) item).getWeightPercentage()));
            fields.addAll(Arrays.asList("Exam duration (minutes)", duration, "Weight of grade (%)", weight));
        }
        while (Ui.form(this, "Edit " + item.getItemCategory(), item.getCourse().getCourseCode()
                + "  \u00b7  its study blocks are planned again after saving", "edit", "Save Changes", fields.toArray())) {
            try {
                String t = title.getText();
                LocalDate d = due.getDate();
                double h = number(hours, "Estimated total hours");
                // build a throw-away copy first: it checks every value, so a mistake changes nothing
                if (item instanceof Assignment) {
                    int level = (Integer) complexity.getSelectedItem();
                    new Assignment(null, t, d, h, level);
                    ((Assignment) item).setComplexityLevel(level);
                } else if (item instanceof Quiz) {
                    int ch = whole(chapter, "Chapter number");
                    new Quiz(null, t, d, h, ch);
                    ((Quiz) item).setChapterNumber(ch);
                } else if (item instanceof Exam) {
                    int min = whole(duration, "Exam duration");
                    double w = number(weight, "Exam weight");
                    new Exam(null, t, d, h, min, w);
                    ((Exam) item).setExamDurationMinutes(min);
                    ((Exam) item).setWeightPercentage(w);
                }
                item.setTitle(t);
                item.setDueDate(d);
                item.setEstimatedHours(h);
                if (!storage.saveItem(item)) {
                    afterSave(false);
                }
                requestGenerateSchedule(false);
                selectTask(item);
                toast(item.getItemCategory() + " updated  \u00b7  study blocks planned again");
                return;
            } catch (IllegalArgumentException ex) {
                Ui.warn(this, ex.getMessage());
            }
        }
    }

    /** Marks the selected task as done (for example when finished early), or reopens it. */
    private void toggleTaskDone() {
        AcademicItem item = selectedTask();
        if (item == null) {
            Ui.warn(this, "Select a task first.");
            return;
        }
        if (item.isCompleted()) {
            item.reopen();
            if (item.isCompleted()) {
                Ui.warn(this, "All planned hours of this task are done. Raise its estimated hours to reopen it.");
                return;
            }
        } else {
            item.markCompleted();
        }
        storage.saveItem(item);
        requestGenerateSchedule(false);
        selectTask(item);
        toast(item.isCompleted() ? "\"" + item.getTitle() + "\" marked as done" : "\"" + item.getTitle() + "\" reopened and planned again");
    }

    private void deleteTask() {
        AcademicItem item = selectedTask();
        if (item == null) {
            Ui.warn(this, "Select a task first.");
            return;
        }
        if (!Ui.confirm(this, "Delete task?", "\"" + item.getTitle() + "\" (" + item.getItemCategory()
                + ") and its study blocks will be deleted.")) {
            return;
        }
        item.getCourse().removeAcademicItem(item);
        requestGenerateSchedule(false);
        toast(item.getItemCategory() + " deleted");
    }

    /** FR3: the daily study limit from the number box on the Workload Summary tab. */
    private void changeDailyLimit(double hours) {
        if (refreshing || Math.abs(hours - storage.getDailyLimit()) < 1e-9) {
            return;
        }
        storage.setDailyLimit(hours);
        engine.setMaxDailyHoursLimit(hours);
        warnings = new ArrayList<>(engine.detectConflicts(storage.getCoursesAndTasks(), engine.getAllBlocks()));
        afterSave(storage.saveNow());
        refreshAll();
        int over = overloadCount();
        toast("Daily limit set to " + Ui.hours(hours) + "  \u00b7  " + (over == 0 ? "no day is over it" : Ui.plural(over, "day is", "days are") + " over it"));
    }

    // ================================================================== building the window

    private JComponent topBar() {
        JPanel bar = new JPanel(new BorderLayout(24, 0)) {
            protected void paintComponent(Graphics g) {
                Graphics2D g2 = (Graphics2D) g.create();
                g2.setPaint(new GradientPaint(0, 0, Ui.NAVY_2, getWidth(), 0, Ui.NAVY));
                g2.fillRect(0, 0, getWidth(), getHeight());
                g2.dispose();
            }
        };
        bar.setBorder(new EmptyBorder(0, 22, 0, 22));
        bar.setPreferredSize(new Dimension(10, 66));

        JPanel names = new JPanel();
        names.setLayout(new BoxLayout(names, BoxLayout.Y_AXIS));
        names.setOpaque(false);
        names.add(Ui.fullWidth(Ui.label("Study Planner", Font.BOLD, 15, Color.WHITE)));
        names.add(Ui.fullWidth(Ui.label("and Exam Scheduler", Font.PLAIN, 11, Ui.NAV_TEXT)));
        JPanel brand = new JPanel(new BorderLayout(10, 0));
        brand.setOpaque(false);
        brand.add(new JLabel(Ui.icon("logo", 36, Color.WHITE)), BorderLayout.WEST);
        brand.add(names, BorderLayout.CENTER);

        JPanel tabs = new JPanel();
        tabs.setLayout(new BoxLayout(tabs, BoxLayout.X_AXIS));   // one row that never wraps, even in a small window
        tabs.setOpaque(false);
        for (String t : TABS) {
            JButton b = tabButton(t);
            tabButtons.put(t, b);
            if (tabs.getComponentCount() > 0) {
                tabs.add(Box.createHorizontalStrut(4));
            }
            tabs.add(b);
        }
        JPanel tabsBox = new JPanel(new GridBagLayout());
        tabsBox.setOpaque(false);
        GridBagConstraints c = new GridBagConstraints();
        c.anchor = GridBagConstraints.WEST;
        c.weightx = 1;
        tabsBox.add(tabs, c);

        saveState = Ui.label("Auto-save is on", Font.PLAIN, 12, Ui.NAV_TEXT);
        saveState.setIcon(Ui.icon("dot", 10, new Color(74, 222, 128)));
        saveState.setIconTextGap(8);

        bar.add(centred(brand), BorderLayout.WEST);
        bar.add(tabsBox, BorderLayout.CENTER);
        bar.add(centred(saveState), BorderLayout.EAST);
        return bar;
    }

    private JButton tabButton(final String name) {
        final String iconKind = name.equals(DASHBOARD) ? "Dashboard" : name.equals(COURSES) ? "Courses" : "Reports";
        JButton b = new JButton(name) {
            protected void paintComponent(Graphics g) {
                Graphics2D g2 = (Graphics2D) g.create();
                Ui.smooth(g2);
                if (name.equals(currentTab)) {
                    g2.setColor(Ui.NAVY_3);
                    g2.fillRoundRect(0, 0, getWidth(), getHeight(), 12, 12);
                    g2.setColor(new Color(118, 156, 255));
                    g2.fillRoundRect(14, getHeight() - 4, getWidth() - 28, 3, 3, 3);
                } else if (getModel().isRollover()) {
                    g2.setColor(new Color(255, 255, 255, 18));
                    g2.fillRoundRect(0, 0, getWidth(), getHeight(), 12, 12);
                }
                g2.dispose();
                super.paintComponent(g);
            }
        };
        b.putClientProperty("iconKind", iconKind);
        Ui.flat(b);
        b.setFocusable(false);
        b.setIcon(Ui.icon(iconKind, 17, Ui.NAV_TEXT));
        b.setIconTextGap(8);
        b.setBorder(new EmptyBorder(10, 12, 11, 12));
        b.setFont(new Font(Ui.FONT, Font.BOLD, 14));
        Dimension bold = b.getPreferredSize();             // keep the width of the bold text (plus room for display
        b.setPreferredSize(new Dimension(bold.width + 14, bold.height));   // scaling), so tabs never jump or clip
        b.setFont(new Font(Ui.FONT, Font.PLAIN, 14));
        b.setForeground(Ui.NAV_TEXT);
        b.addActionListener(e -> showTab(name));
        return b;
    }

    private void showTab(String name) {
        currentTab = name;
        cards.show(pages, name);
        for (Map.Entry<String, JButton> e : tabButtons.entrySet()) {
            boolean on = e.getKey().equals(name);
            JButton b = e.getValue();
            b.setForeground(on ? Color.WHITE : Ui.NAV_TEXT);
            b.setFont(new Font(Ui.FONT, on ? Font.BOLD : Font.PLAIN, 14));
            b.setIcon(Ui.icon((String) b.getClientProperty("iconKind"), 17, on ? Color.WHITE : Ui.NAV_TEXT));
            b.repaint();
        }
        refreshAll();
    }

    /** Tab 1: alert banner on top, today's and upcoming study blocks below (section 5.4, item 1). */
    private JPanel dashboardTab() {
        JPanel p = Ui.page();
        dashSubtitle = Ui.label(" ", Font.PLAIN, 13, Ui.MUTED);
        JButton generate = Ui.button("Generate Schedule", "spark", "primary");
        generate.setToolTipText("Plan the daily study blocks again from today");
        generate.addActionListener(e -> requestGenerateSchedule(true));
        p.add(Ui.pageHead(DASHBOARD, dashSubtitle, generate), BorderLayout.NORTH);

        alertList = Ui.stack();
        alertNote = Ui.note();
        JPanel alerts = Ui.section("Alerts and conflicts", alertNote, Ui.scrollList(alertList));
        alerts.setPreferredSize(new Dimension(10, 196));

        blockModel = new DefaultTableModel(new String[]{"Date", "Course Code", "Task Title", "Allocated Hours", "Mark Complete"}, 0) {
            public boolean isCellEditable(int r, int c) {
                return false;
            }

            public Class<?> getColumnClass(int c) {
                return c == 4 ? Boolean.class : c == 3 ? Double.class : Object.class;
            }
        };
        blockTable = Ui.table(blockModel, "Schedule", "No study blocks yet. Add tasks on the Courses & Tasks tab.");
        Ui.widths(blockTable, 190, 120, 300, 140, 160);
        blockTable.addMouseListener(new MouseAdapter() {
            public void mouseClicked(MouseEvent e) {
                int row = blockTable.rowAtPoint(e.getPoint()), col = blockTable.columnAtPoint(e.getPoint());
                if (row >= 0 && ((col == 4 && e.getClickCount() == 1) || (col != 4 && e.getClickCount() == 2))) {
                    toggleBlock(row);
                }
            }
        });
        blockTable.addMouseMotionListener(new MouseMotionAdapter() {
            public void mouseMoved(MouseEvent e) {
                blockTable.setCursor(blockTable.columnAtPoint(e.getPoint()) == 4 ? Ui.HAND : Cursor.getDefaultCursor());
            }
        });
        Ui.onKey(blockTable, "SPACE", () -> toggleBlock(blockTable.getSelectedRow()));

        todayNote = Ui.label(" ", Font.PLAIN, 12, Ui.MUTED);
        todayBar = Ui.bar(Ui.GREEN);
        todayBar.setPreferredSize(new Dimension(110, 6));
        JPanel today = new JPanel(new FlowLayout(FlowLayout.RIGHT, 10, 0));
        today.setOpaque(false);
        today.add(todayNote);
        today.add(centred(todayBar));
        JPanel blocks = Ui.section("Today's and upcoming study blocks", today, Ui.tableScroll(blockTable));

        JPanel body = new JPanel(new BorderLayout(0, 16));
        body.setOpaque(false);
        body.add(alerts, BorderLayout.NORTH);
        body.add(blocks, BorderLayout.CENTER);
        p.add(body, BorderLayout.CENTER);
        return p;
    }

    /** Tab 2: course list on the left, the selected course's tasks on the right, input bar at the bottom (item 2). */
    private JPanel coursesTab() {
        JPanel p = Ui.page();
        p.add(Ui.pageHead(COURSES, Ui.label("Add your courses, then their assignments, quizzes and exams. Double-click a row to edit it.",
                Font.PLAIN, 13, Ui.MUTED)), BorderLayout.NORTH);

        courseList = new JList<>(courseModel);
        courseList.setCellRenderer(courseRenderer());
        courseList.setSelectionMode(ListSelectionModel.SINGLE_SELECTION);
        courseList.setFixedCellWidth(10);   // cells take the list's width, so long names end in "..." instead of scrolling
        courseList.addListSelectionListener(e -> {
            if (!e.getValueIsAdjusting() && !refreshing) {
                refreshTasks();
            }
        });
        courseList.addMouseListener(new MouseAdapter() {
            public void mouseClicked(MouseEvent e) {
                if (e.getClickCount() == 2 && courseList.locationToIndex(e.getPoint()) >= 0) {
                    editCourse();
                }
            }
        });
        Ui.onKey(courseList, "DELETE", this::deleteCourse);
        JButton addCourse = Ui.button("Add Course", "plus", "primary");
        addCourse.addActionListener(e -> addCourse());
        editCourseButton = Ui.button("Edit", "edit", "plain");
        editCourseButton.addActionListener(e -> editCourse());
        deleteCourseButton = Ui.button("Delete", "trash", "soft-danger");
        deleteCourseButton.addActionListener(e -> deleteCourse());
        JPanel courseButtons = new JPanel(new GridLayout(1, 2, 10, 0));
        courseButtons.setOpaque(false);
        courseButtons.add(editCourseButton);
        courseButtons.add(deleteCourseButton);
        JPanel listBody = new JPanel(new BorderLayout(0, 12));
        listBody.setOpaque(false);
        JScrollPane listScroll = Ui.scroll(courseList);
        listScroll.setHorizontalScrollBarPolicy(JScrollPane.HORIZONTAL_SCROLLBAR_NEVER);
        listBody.add(listScroll, BorderLayout.CENTER);
        listBody.add(courseButtons, BorderLayout.SOUTH);
        JPanel left = Ui.section("Courses", addCourse, listBody);
        left.setPreferredSize(new Dimension(272, 10));

        taskModel = new DefaultTableModel(new String[]{"Task Title", "Type", "Due Date", "Progress", "Hours Left", "Status"}, 0) {
            public boolean isCellEditable(int r, int c) {
                return false;
            }

            public Class<?> getColumnClass(int c) {
                return c == 3 || c == 4 ? Double.class : Object.class;
            }
        };
        taskTable = Ui.table(taskModel, "Assessments", "No tasks yet. Add an assignment, quiz or exam below.");
        taskTable.setRowHeight(54);   // title plus a second line with the type's own details
        Ui.widths(taskTable, 195, 98, 145, 100, 106, 96);
        taskTable.getSelectionModel().addListSelectionListener(e -> updateTaskButtons());
        taskTable.addMouseListener(new MouseAdapter() {
            public void mouseClicked(MouseEvent e) {
                if (e.getClickCount() == 2 && taskTable.rowAtPoint(e.getPoint()) >= 0) {
                    editTask();
                }
            }
        });
        Ui.onKey(taskTable, "DELETE", this::deleteTask);
        editTaskButton = Ui.button("Edit", "edit", "plain");
        editTaskButton.addActionListener(e -> editTask());
        doneTaskButton = Ui.button("Mark Done", "check", "success");
        doneTaskButton.addActionListener(e -> toggleTaskDone());
        deleteTaskButton = Ui.button("Delete", "trash", "soft-danger");
        deleteTaskButton.addActionListener(e -> deleteTask());
        JPanel taskButtons = new JPanel(new FlowLayout(FlowLayout.RIGHT, 8, 0));
        taskButtons.setOpaque(false);
        taskButtons.add(editTaskButton);
        taskButtons.add(doneTaskButton);
        taskButtons.add(deleteTaskButton);
        taskHeading = Ui.label(" ", Font.BOLD, 15, Ui.INK);
        taskNote = Ui.label(" ", Font.PLAIN, 12, Ui.MUTED);
        JPanel headingBox = new JPanel();
        headingBox.setLayout(new BoxLayout(headingBox, BoxLayout.Y_AXIS));
        headingBox.setOpaque(false);
        headingBox.add(Ui.fullWidth(taskHeading));
        headingBox.add(Box.createVerticalStrut(2));
        headingBox.add(Ui.fullWidth(taskNote));
        JPanel tasks = Ui.section(headingBox, centred(taskButtons), Ui.tableScroll(taskTable));

        JPanel right = new JPanel(new BorderLayout(0, 16));
        right.setOpaque(false);
        right.add(tasks, BorderLayout.CENTER);
        right.add(inputBar(), BorderLayout.SOUTH);
        JPanel body = new JPanel(new BorderLayout(16, 0));
        body.setOpaque(false);
        body.add(left, BorderLayout.WEST);
        body.add(right, BorderLayout.CENTER);
        p.add(body, BorderLayout.CENTER);
        return p;
    }

    /** The input bar: task title, type drop-down, due date picker and estimated total hours (plus the type's own fields). */
    private JPanel inputBar() {
        newTitle = Ui.field("", "e.g. Midterm Exam");
        newType = Ui.combo(TYPES);
        newDue = new DatePicker(LocalDate.now().plusDays(7));
        newHours = Ui.field("6", "e.g. 6 or 2.5");
        newComplexity = Ui.combo(LEVELS);
        newComplexity.setSelectedItem(3);
        newChapter = Ui.field("1", "e.g. 4");
        newDuration = Ui.field("120", "minutes");
        newWeight = Ui.field("25", "0 to 100");
        extraFields = new JPanel(extraCards);
        extraFields.setOpaque(false);
        extraFields.add(row(sized(Ui.labelled("Complexity (1-5)", newComplexity), 160)), "Assignment");
        extraFields.add(row(sized(Ui.labelled("Chapter number", newChapter), 160)), "Quiz");
        extraFields.add(row(sized(Ui.labelled("Exam duration (minutes)", newDuration), 190),
                sized(Ui.labelled("Weight of grade (%)", newWeight), 170)), "Exam");
        newType.addActionListener(e -> extraCards.show(extraFields, (String) newType.getSelectedItem()));
        addTaskButton = Ui.button("Add Task", "plus", "primary");
        addTaskButton.addActionListener(e -> addTaskFromInputBar());
        for (JTextField f : new JTextField[]{newTitle, newHours, newChapter, newDuration, newWeight, newDue.getTextField()}) {
            f.addActionListener(e -> addTaskFromInputBar());   // Enter adds the task
        }

        JPanel grid = new JPanel(new GridBagLayout());
        grid.setOpaque(false);
        GridBagConstraints c = new GridBagConstraints();
        c.gridy = 0;
        c.fill = GridBagConstraints.HORIZONTAL;
        c.anchor = GridBagConstraints.SOUTHWEST;
        c.insets = new Insets(0, 0, 12, 12);
        c.weightx = 1;
        grid.add(Ui.labelled("Task Title", newTitle), c);
        c.weightx = 0;
        grid.add(sized(Ui.labelled("Type", newType), 140), c);
        grid.add(sized(Ui.labelled("Due Date", newDue), 180), c);
        c.insets = new Insets(0, 0, 12, 0);
        grid.add(sized(Ui.labelled("Estimated Total Hours", newHours), 160), c);
        c.gridy = 1;
        c.gridwidth = 3;
        c.insets = new Insets(0, 0, 0, 12);
        grid.add(extraFields, c);
        c.gridwidth = 1;
        c.fill = GridBagConstraints.NONE;
        c.anchor = GridBagConstraints.SOUTHEAST;
        c.insets = new Insets(0, 0, 0, 0);
        grid.add(addTaskButton, c);

        addHeading = Ui.label("Add a task", Font.BOLD, 15, Ui.INK);
        JLabel hint = Ui.label("Study blocks are planned automatically: remaining hours \u00f7 days left", Font.PLAIN, 12, Ui.MUTED);
        return Ui.section(addHeading, hint, grid);
    }

    /** Tab 3: totals, the daily limit number box and one row per course (item 3). */
    private JPanel summaryTab() {
        JPanel p = Ui.page();
        p.add(Ui.pageHead(SUMMARY, Ui.label("Completed tasks and remaining study hours per course", Font.PLAIN, 13, Ui.MUTED)),
                BorderLayout.NORTH);
        JPanel statsRow = new JPanel(new GridLayout(1, 4, 16, 0));
        statsRow.setOpaque(false);
        statsRow.add(statCard("Total tasks", "Assessments", Ui.VIOLET, Ui.VIOLET_SOFT, null));
        completedBar = Ui.bar(Ui.GREEN);
        statsRow.add(statCard("Completed tasks", "check", Ui.GREEN, Ui.GREEN_SOFT, completedBar));
        statsRow.add(statCard("Remaining study hours", "Clock", Ui.AMBER, Ui.AMBER_SOFT, null));
        statsRow.add(limitCard());

        summaryModel = new DefaultTableModel(new String[]{"Course Code", "Course Title", "Total Tasks", "Completed Tasks",
                "Remaining Study Hours", "Progress"}, 0) {
            public boolean isCellEditable(int r, int c) {
                return false;
            }

            public Class<?> getColumnClass(int c) {
                return c >= 4 ? Double.class : Object.class;
            }
        };
        JTable table = Ui.table(summaryModel, "Reports", "Add courses and tasks to see the workload summary.");
        Ui.widths(table, 110, 260, 100, 130, 180, 200);
        summaryNote = Ui.note();
        JPanel tableCard = Ui.section("Workload per course", summaryNote, Ui.tableScroll(table));

        JPanel body = new JPanel(new BorderLayout(0, 16));
        body.setOpaque(false);
        body.add(statsRow, BorderLayout.NORTH);
        body.add(tableCard, BorderLayout.CENTER);
        p.add(body, BorderLayout.CENTER);
        return p;
    }

    private JPanel statCard(String name, String iconKind, Color accent, Color soft, JComponent meter) {
        JLabel caption = new JLabel(name.toUpperCase(Locale.ENGLISH));
        caption.setFont(Ui.caps(10));
        caption.setForeground(Ui.MUTED);
        JLabel value = Ui.label("0", Font.BOLD, 28, Ui.INK);
        stats.put("value:" + name, value);
        JPanel mid = new JPanel(new BorderLayout());
        mid.setOpaque(false);
        mid.add(value, BorderLayout.WEST);
        mid.add(new JLabel(Ui.badge(iconKind, 40, accent, soft)), BorderLayout.EAST);
        JLabel sub = Ui.label(" ", Font.PLAIN, 12, Ui.MUTED);
        stats.put("sub:" + name, sub);
        JPanel south = new JPanel(new BorderLayout(0, 8));
        south.setOpaque(false);
        south.add(meter != null ? meter : Box.createRigidArea(new Dimension(0, 6)), BorderLayout.NORTH);
        south.add(sub, BorderLayout.CENTER);
        JPanel c = Ui.card(new BorderLayout(0, 4));
        c.setBorder(new EmptyBorder(16, 16, 20, 16));
        c.add(caption, BorderLayout.NORTH);
        c.add(mid, BorderLayout.CENTER);
        c.add(south, BorderLayout.SOUTH);
        return c;
    }

    /** The number box for the Max Daily Study Hours Limit. */
    private JPanel limitCard() {
        JLabel caption = new JLabel("MAX DAILY STUDY HOURS");
        caption.setFont(Ui.caps(10));
        caption.setForeground(Ui.MUTED);
        limitBox = new JSpinner(new SpinnerNumberModel(storage.getDailyLimit(), 0.5, 24.0, 0.5));
        limitBox.setEditor(new JSpinner.NumberEditor(limitBox, "0.0"));
        styleSpinner(limitBox);
        limitBox.addChangeListener(e -> changeDailyLimit(((Number) limitBox.getValue()).doubleValue()));
        JPanel mid = new JPanel(new FlowLayout(FlowLayout.LEFT, 0, 0));
        mid.setOpaque(false);
        mid.add(limitBox);
        mid.add(Box.createHorizontalStrut(10));
        mid.add(Ui.label("hrs per day", Font.PLAIN, 12, Ui.MUTED));
        limitNote = Ui.label(" ", Font.PLAIN, 12, Ui.MUTED);
        JPanel c = Ui.card(new BorderLayout(0, 6));
        c.setBorder(new EmptyBorder(16, 16, 20, 16));
        c.add(caption, BorderLayout.NORTH);
        c.add(mid, BorderLayout.CENTER);
        c.add(limitNote, BorderLayout.SOUTH);
        return c;
    }

    private static void styleSpinner(JSpinner s) {
        s.setUI(new javax.swing.plaf.basic.BasicSpinnerUI() {
            protected Component createNextButton() {
                JButton b = step("chevron-up");
                installNextButtonListeners(b);
                return b;
            }

            protected Component createPreviousButton() {
                JButton b = step("chevron");
                installPreviousButtonListeners(b);
                return b;
            }

            private JButton step(String kind) {
                JButton b = new JButton(Ui.icon(kind, 12, Ui.MUTED));
                Ui.flat(b);
                b.setFocusable(false);
                b.setBorder(new EmptyBorder(0, 8, 0, 8));
                return b;
            }
        });
        s.setBorder(Ui.fieldBorder(new Insets(2, 4, 2, 4)));
        s.setOpaque(false);
        s.getEditor().setOpaque(false);
        JFormattedTextField tf = ((JSpinner.DefaultEditor) s.getEditor()).getTextField();
        tf.setFont(new Font(Ui.FONT, Font.BOLD, 22));
        tf.setForeground(Ui.INK);
        tf.setBorder(new EmptyBorder(1, 8, 1, 2));
        tf.setOpaque(false);
        tf.setColumns(3);
        tf.setHorizontalAlignment(SwingConstants.LEFT);
    }

    // ================================================================== refreshing the views

    private void refreshAll() {
        refreshing = true;
        try {
            refreshDashboard();
            refreshCourseList();
            refreshTasks();
            refreshSummary();
        } finally {
            refreshing = false;
        }
    }

    /** FR4: overdue tasks in red, tasks due within 3 days in yellow, then the conflict warnings; and the block table. */
    private void refreshDashboard() {
        LocalDate today = LocalDate.now();
        dashSubtitle.setText(Ui.LONG_DAY.format(today) + "  \u00b7  daily limit " + Ui.hours(storage.getDailyLimit())
                + "  \u00b7  tick a block when you finish it");

        List<AcademicItem> items = new ArrayList<>(storage.getCoursesAndTasks());
        items.sort(Comparator.comparing(AcademicItem::getDueDate));
        alertList.removeAll();
        int overdue = 0, soon = 0;
        for (AcademicItem i : items) {
            if (i.isOverdue(today)) {
                overdue++;
                alertList.add(Ui.listRow(Ui.chip("OVERDUE", Ui.RED_SOFT, Ui.RED), 96, i.getTitle(), where(i)
                        + "  \u00b7  was due " + Ui.SHORT_DAY.format(i.getDueDate()) + "  \u00b7  " + Ui.hours(i.getRemainingHours()) + " not done"));
            }
        }
        for (AcademicItem i : items) {
            long d = Ui.days(i.getDueDate());
            if (!i.isCompleted() && d >= 0 && d <= 3) {
                soon++;
                String when = d == 0 ? "DUE TODAY" : d == 1 ? "TOMORROW" : "IN " + d + " DAYS";
                alertList.add(Ui.listRow(Ui.chip(when, Ui.AMBER_SOFT, Ui.AMBER), 96, i.getTitle(), where(i)
                        + "  \u00b7  due " + Ui.SHORT_DAY.format(i.getDueDate()) + "  \u00b7  " + Ui.hours(i.getRemainingHours()) + " left"));
            }
        }
        for (String w : warnings) {
            if (w.startsWith("Conflict")) {
                int open = w.indexOf(" (");
                alertList.add(Ui.listRow(Ui.chip("CONFLICT", Ui.VIOLET_SOFT, Ui.VIOLET), 96,
                        open > 0 ? w.substring(0, open) : w, open > 0 ? w.substring(open + 2, w.length() - 1) : null));
            } else {
                alertList.add(Ui.listRow(Ui.chip("OVERLOAD", Ui.ORANGE_SOFT, Ui.ORANGE), 96, w,
                        "Lower an estimate, move a deadline or raise the daily limit on Workload Summary."));
            }
        }
        if (alertList.getComponentCount() == 0) {
            alertList.add(Ui.emptyRow("check", Ui.GREEN, Ui.GREEN_SOFT, "All clear",
                    "Nothing is overdue, nothing is due in the next 3 days and there are no conflicts."));
        }
        List<String> parts = new ArrayList<>();
        if (overdue > 0) parts.add(overdue + " overdue");
        if (soon > 0) parts.add(soon + " due soon");
        if (!warnings.isEmpty()) parts.add(Ui.plural(warnings.size(), "warning", "warnings"));
        alertNote.setText(parts.isEmpty() ? " " : String.join("  \u00b7  ", parts));
        Ui.relayout(alertList);

        StudyBlock selected = blockTable.getSelectedRow() >= 0 && blockTable.getSelectedRow() < blockRows.size()
                ? blockRows.get(blockTable.getSelectedRow()) : null;
        blockRows.clear();
        for (StudyBlock b : engine.getAllBlocks()) {
            if (!b.getAssignedDate().isBefore(today)) {
                blockRows.add(b);
            }
        }
        blockRows.sort(Comparator.comparing(StudyBlock::getAssignedDate)
                .thenComparing(b -> b.getParentItem().getCourse().getCourseCode())
                .thenComparing(b -> b.getParentItem().getTitle()));
        blockModel.setRowCount(0);
        double planned = 0, done = 0;
        for (StudyBlock b : blockRows) {
            AcademicItem i = b.getParentItem();
            blockModel.addRow(new Object[]{b.getAssignedDate(), i.getCourse().getCourseCode(), i.getTitle(), b.getAllocatedHours(), b.isDone()});
            if (b.getAssignedDate().equals(today)) {
                planned += b.getAllocatedHours();
                if (b.isDone()) done += b.getAllocatedHours();
            }
        }
        int again = blockRows.indexOf(selected);
        if (again >= 0) {
            blockTable.setRowSelectionInterval(again, again);
        }
        boolean over = planned > storage.getDailyLimit() + 1e-9;
        todayNote.setText(planned == 0 ? "Nothing planned for today"
                : "Today: " + String.format(Locale.ENGLISH, "%.1f", done) + " of " + Ui.hours(planned) + " done" + (over ? "  \u00b7  over your limit" : ""));
        todayNote.setForeground(over ? Ui.ORANGE : Ui.MUTED);
        todayBar.putClientProperty("value", planned == 0 ? 0.0 : done / planned);
        todayBar.repaint();
    }

    private void refreshCourseList() {
        Course selected = courseList.getSelectedValue();
        courseModel.clear();
        for (Course c : storage.getCourses()) {
            courseModel.addElement(c);
        }
        if (selected != null && storage.getCourses().contains(selected)) {
            courseList.setSelectedValue(selected, true);
        } else if (!courseModel.isEmpty()) {
            courseList.setSelectedIndex(0);
        }
        boolean has = courseList.getSelectedValue() != null;
        editCourseButton.setEnabled(has);
        deleteCourseButton.setEnabled(has);
    }

    private void refreshTasks() {
        AcademicItem selected = selectedTask();
        Course course = courseList.getSelectedValue();
        taskRows.clear();
        taskModel.setRowCount(0);
        boolean has = course != null;
        editCourseButton.setEnabled(has);
        deleteCourseButton.setEnabled(has);
        addTaskButton.setEnabled(has);
        if (!has) {
            taskHeading.setText("No course yet");
            taskNote.setText("Click Add Course on the left to start.");
            addHeading.setText("Add a task");
        } else {
            taskHeading.setText(course.getCourseCode() + "  \u00b7  " + course.getCourseName());
            taskNote.setText(Ui.plural(course.getItems().size(), "task", "tasks") + "  \u00b7  " + course.getCompletedTaskCount()
                    + " completed  \u00b7  " + Ui.hours(course.getRemainingCourseHours()) + " left");
            addHeading.setText("Add a task to " + course.getCourseCode());
            List<AcademicItem> items = new ArrayList<>(course.getItems());
            items.sort(Comparator.comparing(AcademicItem::getDueDate));
            for (AcademicItem i : items) {
                double done = Math.min(i.getEstimatedHours(), i.getCompletedHours());
                taskModel.addRow(new Object[]{i.getTitle() + "\n" + details(i), i.getItemCategory(), i.getDueDate(),
                        i.isCompleted() ? 1.0 : done / i.getEstimatedHours(), i.getRemainingHours(), status(i)});
                taskRows.add(i);
            }
        }
        int again = taskRows.indexOf(selected);
        if (again >= 0) {
            taskTable.setRowSelectionInterval(again, again);
        }
        updateTaskButtons();
    }

    private void updateTaskButtons() {
        AcademicItem item = selectedTask();
        editTaskButton.setEnabled(item != null);
        doneTaskButton.setEnabled(item != null);
        deleteTaskButton.setEnabled(item != null);
        boolean reopen = item != null && item.isCompleted();
        doneTaskButton.setText(reopen ? "Reopen" : "Mark Done");
        doneTaskButton.setIcon(Ui.icon(reopen ? "undo" : "check", 16, Color.WHITE));
    }

    /** UC6 / FR5: completed tasks and remaining study hours per course, and the daily limit. */
    private void refreshSummary() {
        int total = 0, completed = 0, open = 0;
        double remaining = 0;
        summaryModel.setRowCount(0);
        for (Course c : storage.getCourses()) {
            double estimated = 0;
            for (AcademicItem i : c.getItems()) {
                estimated += i.getEstimatedHours();
                if (!i.isCompleted()) open++;
            }
            total += c.getItems().size();
            completed += c.getCompletedTaskCount();
            remaining += c.getRemainingCourseHours();
            double progress = estimated == 0 ? 0 : (estimated - c.getRemainingCourseHours()) / estimated;
            summaryModel.addRow(new Object[]{c.getCourseCode(), c.getCourseName(), c.getItems().size(), c.getCompletedTaskCount(),
                    c.getRemainingCourseHours(), progress});
        }
        int courses = storage.getCourses().size();
        stats.get("value:Total tasks").setText(String.valueOf(total));
        stats.get("sub:Total tasks").setText("in " + Ui.plural(courses, "course", "courses"));
        stats.get("value:Completed tasks").setText(String.valueOf(completed));
        stats.get("sub:Completed tasks").setText(total == 0 ? "No tasks yet" : Math.round(100.0 * completed / total) + "% of all tasks");
        completedBar.putClientProperty("value", total == 0 ? 0.0 : completed / (double) total);
        completedBar.repaint();
        stats.get("value:Remaining study hours").setText(String.format(Locale.ENGLISH, "%.1f", remaining));
        stats.get("sub:Remaining study hours").setText("for " + Ui.plural(open, "open task", "open tasks"));
        if (Math.abs(((Number) limitBox.getValue()).doubleValue() - storage.getDailyLimit()) > 1e-9) {
            limitBox.setValue(storage.getDailyLimit());   // ignored by changeDailyLimit while refreshing
        }
        int over = overloadCount();
        limitNote.setText(over == 0 ? "No day is over the limit" : Ui.plural(over, "day is", "days are") + " over the limit");
        limitNote.setForeground(over == 0 ? Ui.GREEN : Ui.ORANGE);
        summaryNote.setText(courses == 0 ? " " : Ui.plural(courses, "course", "courses"));
    }

    // ================================================================== small helpers

    private AcademicItem selectedTask() {
        int r = taskTable.getSelectedRow();
        return r >= 0 && r < taskRows.size() ? taskRows.get(r) : null;
    }

    /** Shows the item's course on the Courses & Tasks tab and selects the item. */
    private void selectTask(AcademicItem item) {
        if (item.getCourse() == null) {
            return;
        }
        if (courseList.getSelectedValue() != item.getCourse()) {
            courseList.setSelectedValue(item.getCourse(), true);
            refreshTasks();
        }
        int r = taskRows.indexOf(item);
        if (r >= 0) {
            taskTable.setRowSelectionInterval(r, r);
            taskTable.scrollRectToVisible(taskTable.getCellRect(r, 0, true));
        }
    }

    private int overloadCount() {
        int n = 0;
        for (String w : warnings) {
            if (w.startsWith("Overload")) n++;
        }
        return n;
    }

    private static String where(AcademicItem i) {
        return i.getCourse().getCourseCode() + "  \u00b7  " + i.getItemCategory();
    }

    private static String status(AcademicItem i) {
        if (i.isCompleted()) return "Completed";
        long d = Ui.days(i.getDueDate());
        if (d < 0) return "Overdue";
        return d <= 3 ? "Due soon" : "Open";
    }

    private static String details(AcademicItem i) {
        if (i instanceof Assignment) return "Complexity " + ((Assignment) i).getComplexityLevel() + "/5";
        if (i instanceof Quiz) return "Chapter " + ((Quiz) i).getChapterNumber();
        if (i instanceof Exam) return ((Exam) i).getExamDurationMinutes() + " min  \u00b7  " + plain(((Exam) i).getWeightPercentage()) + "% of grade";
        return "";
    }

    /** A number typed by the user (a comma also works as the decimal point). */
    private static double number(JTextField f, String what) {
        try {
            return Double.parseDouble(f.getText().trim().replace(',', '.'));
        } catch (NumberFormatException e) {
            throw new IllegalArgumentException(what + " must be a number, for example 6 or 2.5.");
        }
    }

    private static int whole(JTextField f, String what) {
        try {
            return Integer.parseInt(f.getText().trim());
        } catch (NumberFormatException e) {
            throw new IllegalArgumentException(what + " must be a whole number.");
        }
    }

    /** 6.0 becomes "6", 2.5 stays "2.5". */
    private static String plain(double v) {
        return v == Math.rint(v) ? String.valueOf((long) v) : String.valueOf(v);
    }

    private static JPanel centred(JComponent c) {
        JPanel p = new JPanel(new GridBagLayout());
        p.setOpaque(false);
        p.add(c);
        return p;
    }

    private static JComponent sized(JComponent c, int width) {
        c.setPreferredSize(new Dimension(width, c.getPreferredSize().height));
        return c;
    }

    private static JPanel row(JComponent... parts) {
        JPanel p = new JPanel(new FlowLayout(FlowLayout.LEFT, 0, 0));
        p.setOpaque(false);
        for (int i = 0; i < parts.length; i++) {
            if (i > 0) {
                p.add(Box.createHorizontalStrut(12));
            }
            p.add(parts[i]);
        }
        return p;
    }

    private ListCellRenderer<Course> courseRenderer() {
        return (list, course, index, selected, focus) -> {
            JPanel cell = new JPanel(new BorderLayout()) {
                protected void paintComponent(Graphics g) {
                    if (selected) {
                        Graphics2D g2 = (Graphics2D) g.create();
                        Ui.smooth(g2);
                        g2.setColor(Ui.BLUE_SOFT);
                        g2.fillRoundRect(0, 2, getWidth() - 1, getHeight() - 5, 12, 12);
                        g2.setColor(Ui.BLUE);
                        g2.fillRoundRect(0, 12, 4, getHeight() - 25, 4, 4);
                        g2.dispose();
                    }
                }
            };
            cell.setOpaque(false);
            JPanel text = new JPanel();
            text.setLayout(new BoxLayout(text, BoxLayout.Y_AXIS));
            text.setOpaque(false);
            text.add(Ui.fullWidth(Ui.label(course.getCourseCode(), Font.BOLD, 13, Ui.BLUE)));
            text.add(Ui.fullWidth(Ui.label(course.getCourseName(), Font.PLAIN, 13, Ui.INK)));
            text.add(Box.createVerticalStrut(2));
            text.add(Ui.fullWidth(Ui.label(Ui.plural(course.getItems().size(), "task", "tasks") + "  \u00b7  "
                    + Ui.hours(course.getRemainingCourseHours()) + " left", Font.PLAIN, 11, Ui.MUTED)));
            text.setBorder(new EmptyBorder(10, 14, 11, 10));
            cell.add(text, BorderLayout.CENTER);
            return cell;
        };
    }

    /** A short message that appears at the bottom of the window and disappears by itself. */
    private void toast(String message) {
        final JLayeredPane lp = getLayeredPane();
        if (toastLabel != null) {
            lp.remove(toastLabel);
        }
        if (toastTimer != null) {
            toastTimer.stop();
        }
        final JLabel t = Ui.pill(message, Ui.icon("check", 16, new Color(110, 231, 160)), new Color(17, 24, 48, 238), Color.WHITE);
        t.setFont(Ui.F_BOLD);
        t.setIconTextGap(10);
        t.setBorder(new EmptyBorder(12, 16, 12, 20));
        Dimension d = t.getPreferredSize();
        Rectangle area = SwingUtilities.convertRectangle(pages.getParent(), pages.getBounds(), lp);
        t.setBounds(area.x + (area.width - d.width) / 2, area.y + area.height - d.height - 26, d.width, d.height);
        lp.add(t, JLayeredPane.POPUP_LAYER);
        lp.repaint();
        toastLabel = t;
        toastTimer = new javax.swing.Timer(3000, e -> {
            lp.remove(t);
            lp.repaint();
            if (toastLabel == t) {
                toastLabel = null;
            }
        });
        toastTimer.setRepeats(false);
        toastTimer.start();
    }

    /** Shows whether the last change reached the data file. */
    private void afterSave(boolean ok) {
        if (ok) {
            saveState.setText("Saved " + LocalTime.now().withSecond(0).withNano(0));
            saveState.setIcon(Ui.icon("dot", 10, new Color(74, 222, 128)));
            saveState.setToolTipText("Saved in " + storage.getPath());
        } else {
            saveState.setText("Not saved");
            saveState.setIcon(Ui.icon("dot", 10, new Color(248, 113, 113)));
            Ui.warn(this, "The data could not be saved: " + storage.getLastError());
        }
    }
}
