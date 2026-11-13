package studyplanner.storage;

import studyplanner.model.AcademicItem;
import studyplanner.model.Assignment;
import studyplanner.model.Course;
import studyplanner.model.Exam;
import studyplanner.model.Quiz;
import studyplanner.model.StudyBlock;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.StandardCopyOption;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Saves and loads all courses, academic items, study blocks and the daily study limit in one local JSON file
 * (boundary / data access class). No network and no third-party library are used.
 *
 * <p>The file is written to a temporary file first and then moved over the real one, so a crash cannot leave half a
 * file. A file that cannot be read is kept as {@code *.corrupt} and the planner starts with empty data.
 */
public class StorageManager {
    /** Default location, relative to the folder the program is started from (the project folder in NetBeans). */
    public static final String DEFAULT_FILE = "data/study-planner.json";
    /** Daily study limit used when none has been saved yet. */
    public static final double DEFAULT_DAILY_LIMIT = 5.0;
    private static final int FORMAT_VERSION = 1;

    private final String dbPathOrJsonFile;
    private final List<Course> courses = new ArrayList<>();
    private double dailyLimit = DEFAULT_DAILY_LIMIT;
    private String loadWarning;
    private String lastError;

    public StorageManager() {
        this(DEFAULT_FILE);
    }

    public StorageManager(String dbPathOrJsonFile) {
        this.dbPathOrJsonFile = dbPathOrJsonFile;
    }

    // ------------------------------------------------------------------ operations from the class diagram

    /**
     * Reads every course (with its items and their study blocks) from the JSON file. A missing file gives empty data;
     * a damaged file is renamed to *.corrupt and {@link #getLoadWarning()} explains what happened.
     */
    public List<Course> loadAll() {
        courses.clear();
        dailyLimit = DEFAULT_DAILY_LIMIT;
        loadWarning = null;
        Path file = Paths.get(dbPathOrJsonFile);
        if (!Files.exists(file)) {
            return getCourses();
        }
        try {
            String text = new String(Files.readAllBytes(file), StandardCharsets.UTF_8);
            readRoot(Json.parse(text));
        } catch (Exception e) {
            courses.clear();
            dailyLimit = DEFAULT_DAILY_LIMIT;
            Path bad = Paths.get(dbPathOrJsonFile + ".corrupt");
            boolean kept;
            try {
                Files.move(file, bad, StandardCopyOption.REPLACE_EXISTING);
                kept = true;
            } catch (IOException moveFailed) {
                kept = false;
            }
            loadWarning = "The data file could not be read (" + e.getMessage() + "). "
                    + (kept ? "It was kept as " + bad.getFileName() + " and " : "")
                    + "the planner started with empty data.";
        }
        return getCourses();
    }

    /** Writes the given courses and the daily study limit to the JSON file. */
    public void saveAll(List<Course> courses) throws IOException {
        Map<String, Object> root = new LinkedHashMap<>();
        root.put("version", FORMAT_VERSION);
        root.put("maxDailyHours", dailyLimit);
        List<Object> list = new ArrayList<>();
        for (Course c : courses) {
            list.add(courseToMap(c));
        }
        root.put("courses", list);

        Path file = Paths.get(dbPathOrJsonFile).toAbsolutePath();
        if (file.getParent() != null) {
            Files.createDirectories(file.getParent());
        }
        Path tmp = Paths.get(file + ".tmp");
        Files.write(tmp, Json.write(root).getBytes(StandardCharsets.UTF_8));
        try {
            Files.move(tmp, file, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
        } catch (AtomicMoveNotSupportedException e) {
            Files.move(tmp, file, StandardCopyOption.REPLACE_EXISTING);
        }
    }

    // ------------------------------------------------------------------ operations from the sequence diagrams

    /** Sequence 1, step 2: saves after an item was added or changed. Returns true as the "confirmSaved" answer. */
    public boolean saveItem(AcademicItem item) {
        return saveNow();
    }

    /** Sequence 1, step 5: every academic item of every course (the "taskList"). */
    public List<AcademicItem> getCoursesAndTasks() {
        List<AcademicItem> all = new ArrayList<>();
        for (Course c : courses) {
            all.addAll(c.getItems());
        }
        return all;
    }

    /** Sequence 2: saves after a study block was ticked off. Returns true when the data was saved. */
    public boolean updateProgress() {
        return saveNow();
    }

    /** Saves the current data; on failure returns false and {@link #getLastError()} says why. */
    public boolean saveNow() {
        try {
            saveAll(courses);
            lastError = null;
            return true;
        } catch (IOException | RuntimeException e) {
            lastError = e.getMessage();
            return false;
        }
    }

    // ------------------------------------------------------------------ the course list kept in memory

    public List<Course> getCourses() {
        return Collections.unmodifiableList(courses);
    }

    public void addCourse(Course course) {
        if (findCourse(course.getCourseCode()) != null) {
            throw new IllegalArgumentException("Course code " + course.getCourseCode() + " already exists.");
        }
        courses.add(course);
    }

    /** Removes a course; its academic items and study blocks go with it (composition). */
    public void removeCourse(Course course) {
        courses.remove(course);
    }

    /** The course with this code (ignoring upper/lower case), or null. */
    public Course findCourse(String code) {
        for (Course c : courses) {
            if (c.getCourseCode().equalsIgnoreCase(code == null ? "" : code.trim())) {
                return c;
            }
        }
        return null;
    }

    public double getDailyLimit() {
        return dailyLimit;
    }

    /** Daily study limit in hours, from 0.5 to 24. */
    public void setDailyLimit(double hours) {
        if (!(hours >= 0.5) || hours > 24) {
            throw new IllegalArgumentException("The daily limit must be from 0.5 to 24 hours.");
        }
        dailyLimit = hours;
    }

    /** Set by {@link #loadAll()} when the file was damaged; null when loading went well. */
    public String getLoadWarning() {
        return loadWarning;
    }

    /** Why the last save failed, or null. */
    public String getLastError() {
        return lastError;
    }

    public String getPath() {
        return Paths.get(dbPathOrJsonFile).toAbsolutePath().toString();
    }

    // ------------------------------------------------------------------ JSON mapping

    private static Map<String, Object> courseToMap(Course c) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("courseCode", c.getCourseCode());
        m.put("courseName", c.getCourseName());
        List<Object> items = new ArrayList<>();
        for (AcademicItem i : c.getItems()) {
            items.add(itemToMap(i));
        }
        m.put("items", items);
        return m;
    }

    private static Map<String, Object> itemToMap(AcademicItem i) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("category", i.getItemCategory());
        m.put("id", i.getId());
        m.put("title", i.getTitle());
        m.put("dueDate", i.getDueDate().toString());
        m.put("estimatedHours", i.getEstimatedHours());
        m.put("completedHours", i.getCompletedHours());
        m.put("isCompleted", i.isCompleted());
        if (i instanceof Assignment) {
            m.put("complexityLevel", ((Assignment) i).getComplexityLevel());
        } else if (i instanceof Quiz) {
            m.put("chapterNumber", ((Quiz) i).getChapterNumber());
        } else if (i instanceof Exam) {
            m.put("examDurationMinutes", ((Exam) i).getExamDurationMinutes());
            m.put("weightPercentage", ((Exam) i).getWeightPercentage());
        }
        List<Object> blocks = new ArrayList<>();
        for (StudyBlock b : i.getBlocks()) {
            Map<String, Object> bm = new LinkedHashMap<>();
            bm.put("blockId", b.getBlockId());
            bm.put("assignedDate", b.getAssignedDate().toString());
            bm.put("allocatedHours", b.getAllocatedHours());
            bm.put("isDone", b.isDone());
            blocks.add(bm);
        }
        m.put("blocks", blocks);
        return m;
    }

    private void readRoot(Object parsed) {
        Map<String, Object> root = map(parsed, "the file");
        if (root.containsKey("maxDailyHours")) {
            setDailyLimit(num(root, "maxDailyHours"));
        }
        for (Object co : list(root, "courses")) {
            Map<String, Object> cm = map(co, "a course");
            Course course = new Course(str(cm, "courseCode"), str(cm, "courseName"));
            for (Object io : list(cm, "items")) {
                course.addAcademicItem(readItem(map(io, "an item")));
            }
            addCourse(course);
        }
    }

    private static AcademicItem readItem(Map<String, Object> m) {
        String id = str(m, "id"), title = str(m, "title"), category = str(m, "category");
        LocalDate due = LocalDate.parse(str(m, "dueDate"));
        double hours = num(m, "estimatedHours");
        AcademicItem item;
        switch (category) {
            case "Assignment":
                item = new Assignment(id, title, due, hours, (int) num(m, "complexityLevel"));
                break;
            case "Quiz":
                item = new Quiz(id, title, due, hours, (int) num(m, "chapterNumber"));
                break;
            case "Exam":
                item = new Exam(id, title, due, hours, (int) num(m, "examDurationMinutes"), num(m, "weightPercentage"));
                break;
            default:
                throw new IllegalArgumentException("unknown item category \"" + category + "\"");
        }
        item.restoreProgress(num(m, "completedHours"), bool(m, "isCompleted"));
        for (Object bo : list(m, "blocks")) {
            Map<String, Object> bm = map(bo, "a study block");
            new StudyBlock(str(bm, "blockId"), LocalDate.parse(str(bm, "assignedDate")),
                    num(bm, "allocatedHours"), bool(bm, "isDone"), item);
        }
        return item;
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> map(Object o, String what) {
        if (!(o instanceof Map)) {
            throw new IllegalArgumentException(what + " is not a JSON object");
        }
        return (Map<String, Object>) o;
    }

    private static List<?> list(Map<String, Object> m, String key) {
        Object o = m.get(key);
        if (o == null) {
            return Collections.emptyList();
        }
        if (!(o instanceof List)) {
            throw new IllegalArgumentException("\"" + key + "\" is not a list");
        }
        return (List<?>) o;
    }

    private static String str(Map<String, Object> m, String key) {
        Object o = m.get(key);
        if (!(o instanceof String)) {
            throw new IllegalArgumentException("\"" + key + "\" is missing");
        }
        return (String) o;
    }

    private static double num(Map<String, Object> m, String key) {
        Object o = m.get(key);
        if (!(o instanceof Number)) {
            throw new IllegalArgumentException("\"" + key + "\" is not a number");
        }
        return ((Number) o).doubleValue();
    }

    private static boolean bool(Map<String, Object> m, String key) {
        Object o = m.get(key);
        if (!(o instanceof Boolean)) {
            throw new IllegalArgumentException("\"" + key + "\" is not true or false");
        }
        return (Boolean) o;
    }
}
