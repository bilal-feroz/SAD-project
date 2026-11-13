package studyplanner.engine;

import studyplanner.model.AcademicItem;
import studyplanner.model.Exam;
import studyplanner.model.StudyBlock;
import studyplanner.storage.StorageManager;

import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.TreeMap;

/**
 * Makes the daily study plan and finds conflicts (control class).
 *
 * <p>Scheduling is day-based on purpose: for every unfinished item the remaining study hours are divided evenly
 * over the calendar days that are left, giving one {@link StudyBlock} per day and no clock times. How many days are
 * left is decided by the item itself ({@link AcademicItem#calculateDailyHours(LocalDate)}), so assignments, quizzes and
 * exams can follow different rules without this class knowing about them.
 */
public class ScheduleEngine {
    private static final double EPSILON = 1e-6;
    private static final DateTimeFormatter SHORT_DATE = DateTimeFormatter.ofPattern("MMM d", Locale.ENGLISH);

    private double maxDailyHoursLimit;
    private final StorageManager storage;

    public ScheduleEngine(StorageManager storage) {
        this.storage = storage;
        this.maxDailyHoursLimit = storage.getDailyLimit();
    }

    // ------------------------------------------------------------------ Sequence 1: schedule generation

    /** Sequence 1, step 4: plans every unfinished item again from today and checks for conflicts. */
    public ScheduleResult generateSchedule(double maxDailyHours) {
        return generateSchedule(maxDailyHours, LocalDate.now());
    }

    /** Same as {@link #generateSchedule(double)} with a chosen "today" (used by the automated tests). */
    public ScheduleResult generateSchedule(double maxDailyHours, LocalDate today) {
        setMaxDailyHoursLimit(maxDailyHours);
        List<AcademicItem> taskList = storage.getCoursesAndTasks();                    // step 5
        List<StudyBlock> created = generateDailyBlocks(taskList, today);               // step 6
        List<String> warnings = detectDateConflicts(taskList, allBlocks(taskList),
                maxDailyHoursLimit, today);                                            // step 7
        return new ScheduleResult(created, warnings);                                  // step 8
    }

    /**
     * Class model: removes the blocks that are not done yet and creates new daily blocks for every unfinished item.
     * Blocks that are already done are kept as history. Returns the new blocks.
     */
    public List<StudyBlock> generateDailyBlocks(List<AcademicItem> items) {
        return generateDailyBlocks(items, LocalDate.now());
    }

    public List<StudyBlock> generateDailyBlocks(List<AcademicItem> items, LocalDate today) {
        List<StudyBlock> created = new ArrayList<>();
        for (AcademicItem item : items) {
            item.removePendingBlocks();
            created.addAll(divideHoursAcrossDays(item, today));
        }
        return created;
    }

    /**
     * Sequence 1, step 6: the item's remaining hours divided by the days left, one block per calendar day starting
     * today. If today's block of this item is already done, the plan starts tomorrow so the student is not asked to
     * study the same item twice in one day (unless no other day is left).
     */
    private List<StudyBlock> divideHoursAcrossDays(AcademicItem item, LocalDate today) {
        List<StudyBlock> blocks = new ArrayList<>();
        double remaining = item.getRemainingHours();
        if (item.isCompleted() || remaining <= EPSILON) {
            return blocks;
        }
        LocalDate start = item.hasDoneBlockOn(today) ? today.plusDays(1) : today;
        double daily = item.calculateDailyHours(start);          // polymorphic: depends on the kind of item
        if (daily <= EPSILON && !start.equals(today)) {
            start = today;
            daily = item.calculateDailyHours(today);
        }
        if (daily <= EPSILON) {
            return blocks;                                        // no study days left (due today or overdue)
        }
        int days = (int) Math.round(remaining / daily);
        for (int i = 0; i < days; i++) {
            blocks.add(new StudyBlock(start.plusDays(i), daily, item));
        }
        return blocks;
    }

    // ------------------------------------------------------------------ conflicts

    /** Class model: same-day exams and days over the daily study limit, from today on. */
    public List<String> detectConflicts(List<AcademicItem> items, List<StudyBlock> blocks) {
        return detectDateConflicts(items, blocks, maxDailyHoursLimit, LocalDate.now());
    }

    /** Same as {@link #detectConflicts(List, List)} with a chosen "today" (used by the automated tests). */
    public List<String> detectConflicts(List<AcademicItem> items, List<StudyBlock> blocks, LocalDate today) {
        return detectDateConflicts(items, blocks, maxDailyHoursLimit, today);
    }

    /** Sequence 1, step 7: the two checks of FR3, as readable warnings in date order. */
    private List<String> detectDateConflicts(List<AcademicItem> taskList, List<StudyBlock> studyBlocks,
                                             double maxDailyHours, LocalDate today) {
        List<String> warnings = new ArrayList<>();

        // 1. Two or more exams on the same date.
        Map<LocalDate, List<AcademicItem>> examsByDay = new TreeMap<>();
        for (AcademicItem item : taskList) {
            if (item instanceof Exam && !item.getDueDate().isBefore(today)) {
                examsByDay.computeIfAbsent(item.getDueDate(), d -> new ArrayList<>()).add(item);
            }
        }
        for (Map.Entry<LocalDate, List<AcademicItem>> e : examsByDay.entrySet()) {
            if (e.getValue().size() >= 2) {
                List<String> names = new ArrayList<>();
                for (AcademicItem exam : e.getValue()) {
                    names.add(label(exam));
                }
                warnings.add("Conflict Warning: " + e.getValue().size() + " Exams scheduled on " + e.getKey()
                        + " (" + String.join(", ", names) + ")");
            }
        }

        // 2. More study hours on one day than the daily limit. Today counts everything planned for today (done or
        //    not); later days count only blocks that are not done yet, because work done early no longer loads them.
        Map<LocalDate, Double> hoursByDay = new TreeMap<>();
        for (StudyBlock b : studyBlocks) {
            LocalDate day = b.getAssignedDate();
            if (day.equals(today) || (day.isAfter(today) && !b.isDone())) {
                hoursByDay.merge(day, b.getAllocatedHours(), Double::sum);
            }
        }
        for (Map.Entry<LocalDate, Double> e : hoursByDay.entrySet()) {
            if (e.getValue() > maxDailyHours + EPSILON) {
                warnings.add(String.format(Locale.ENGLISH, "Overload Warning: %s requires %.1f hrs, passing your %.1f hr daily limit",
                        SHORT_DATE.format(e.getKey()), e.getValue(), maxDailyHours));
            }
        }
        return warnings;
    }

    private static String label(AcademicItem item) {
        return item.getCourse() == null ? item.getTitle() : item.getCourse().getCourseCode() + " " + item.getTitle();
    }

    // ------------------------------------------------------------------ Sequence 2: progress

    /** Sequence 2: ticks a block off, takes its hours off the parent item and saves. Returns false if not saved. */
    public boolean markBlockComplete(String blockId) {
        StudyBlock block = findBlock(blockId);
        if (block == null) {
            return false;
        }
        block.markBlockDone();
        return storage.updateProgress();
    }

    /** Unticks a block (undo of {@link #markBlockComplete(String)}) and saves. */
    public boolean reopenBlock(String blockId) {
        StudyBlock block = findBlock(blockId);
        if (block == null) {
            return false;
        }
        block.markBlockNotDone();
        return storage.updateProgress();
    }

    /** Every study block of every item, done and not done. */
    public List<StudyBlock> getAllBlocks() {
        return allBlocks(storage.getCoursesAndTasks());
    }

    private static List<StudyBlock> allBlocks(List<AcademicItem> items) {
        List<StudyBlock> all = new ArrayList<>();
        for (AcademicItem item : items) {
            all.addAll(item.getBlocks());
        }
        return all;
    }

    private StudyBlock findBlock(String blockId) {
        for (StudyBlock b : getAllBlocks()) {
            if (b.getBlockId().equals(blockId)) {
                return b;
            }
        }
        return null;
    }

    public double getMaxDailyHoursLimit() {
        return maxDailyHoursLimit;
    }

    public void setMaxDailyHoursLimit(double hours) {
        if (!(hours > 0)) {
            throw new IllegalArgumentException("The daily limit must be more than 0 hours.");
        }
        maxDailyHoursLimit = hours;
    }

    /** What {@link #generateSchedule(double)} returns: the new blocks and the warnings ("blocks + warnings"). */
    public static final class ScheduleResult {
        private final List<StudyBlock> studyBlocks;
        private final List<String> warnings;

        ScheduleResult(List<StudyBlock> studyBlocks, List<String> warnings) {
            this.studyBlocks = Collections.unmodifiableList(studyBlocks);
            this.warnings = Collections.unmodifiableList(warnings);
        }

        public List<StudyBlock> getStudyBlocks() {
            return studyBlocks;
        }

        public List<String> getWarnings() {
            return warnings;
        }
    }
}
