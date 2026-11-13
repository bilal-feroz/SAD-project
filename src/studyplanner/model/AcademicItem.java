package studyplanner.model;

import java.time.LocalDate;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.UUID;

/**
 * Any graded piece of coursework that needs study time before a due date (abstract superclass).
 *
 * <p>The three kinds of item are {@link Assignment}, {@link Quiz} and {@link Exam}. Each one says what it is
 * ({@link #getItemCategory()}) and how its remaining hours are spread over the days that are left
 * ({@link #calculateDailyHours(LocalDate)}); the rest of the program only uses these two methods, so a new kind of
 * item can be added without changing the schedule engine (polymorphism).
 */
public abstract class AcademicItem {
    /** Small tolerance for adding up fractional hours. */
    static final double EPSILON = 1e-6;

    private final String id;
    private String title;
    private LocalDate dueDate;
    private double estimatedHours;
    private double completedHours;
    private boolean isCompleted;
    private Course course;                                    // parent course (set by Course.addAcademicItem)
    private final List<StudyBlock> blocks = new ArrayList<>(); // AcademicItem 1 --* StudyBlock

    /**
     * @param id             unique id, or null to create a new one
     * @param title          what the item is called, for example "Midterm Exam"
     * @param dueDate        the calendar day the item is due or takes place
     * @param estimatedHours total study hours the student expects to need (more than 0)
     */
    protected AcademicItem(String id, String title, LocalDate dueDate, double estimatedHours) {
        this.id = id == null || id.isEmpty() ? UUID.randomUUID().toString() : id;
        setTitle(title);
        setDueDate(dueDate);
        setEstimatedHours(estimatedHours);
    }

    /** "Assignment", "Quiz" or "Exam". */
    public abstract String getItemCategory();

    /**
     * Study hours needed per day from {@code today} on: the remaining hours divided by the calendar days left.
     * This default lets the student study up to and including the due date; subclasses decide for themselves.
     * Returns 0 when the item is completed or no study days are left.
     */
    public double calculateDailyHours(LocalDate today) {
        return spreadUntil(today, dueDate);
    }

    /** Remaining hours divided evenly over the days from {@code today} up to and including {@code lastStudyDay}. */
    protected final double spreadUntil(LocalDate today, LocalDate lastStudyDay) {
        long days = ChronoUnit.DAYS.between(today, lastStudyDay) + 1;
        return days <= 0 ? 0 : getRemainingHours() / days;
    }

    /** Study hours still to do (0 once the item is completed). */
    public double getRemainingHours() {
        return isCompleted ? 0 : Math.max(0, estimatedHours - completedHours);
    }

    /** Marks the whole item as done, for example when the student finished early. */
    public void markCompleted() {
        isCompleted = true;
    }

    /** Undoes {@link #markCompleted()}; the item is open again if it still has hours left. */
    public void reopen() {
        isCompleted = completedHours >= estimatedHours - EPSILON;
    }

    /** True when the due date has passed and the item is not completed. */
    public boolean isOverdue(LocalDate today) {
        return !isCompleted && dueDate.isBefore(today);
    }

    /** Called by {@link StudyBlock} when a block is ticked (positive hours) or unticked (negative hours). */
    void addCompletedHours(double hours) {
        completedHours = Math.max(0, completedHours + hours);
        if (completedHours >= estimatedHours - EPSILON) {
            isCompleted = true;                 // all planned hours are done
        } else if (hours < 0) {
            isCompleted = false;                // a block was unticked, so there is work left again
        }
    }

    /** Called by the StudyBlock constructor: links a new block to this item. */
    void attachBlock(StudyBlock block) {
        blocks.add(block);
    }

    /** Removes and returns the blocks that are not done yet (used before the schedule is made again). */
    public List<StudyBlock> removePendingBlocks() {
        List<StudyBlock> removed = new ArrayList<>();
        for (StudyBlock b : blocks) {
            if (!b.isDone()) {
                removed.add(b);
            }
        }
        blocks.removeAll(removed);
        return removed;
    }

    /** True when a block of this item on that day is already done. */
    public boolean hasDoneBlockOn(LocalDate day) {
        for (StudyBlock b : blocks) {
            if (b.isDone() && b.getAssignedDate().equals(day)) {
                return true;
            }
        }
        return false;
    }

    /** Restores saved progress; used only by the storage layer when loading. */
    public void restoreProgress(double completedHours, boolean isCompleted) {
        this.completedHours = Math.max(0, completedHours);
        this.isCompleted = isCompleted;
    }

    // ------------------------------------------------------------------ getters and validated setters

    public String getId() {
        return id;
    }

    public String getTitle() {
        return title;
    }

    public final void setTitle(String title) {
        if (title == null || title.trim().isEmpty()) {
            throw new IllegalArgumentException("Enter a task title.");
        }
        this.title = title.trim();
    }

    public LocalDate getDueDate() {
        return dueDate;
    }

    public final void setDueDate(LocalDate dueDate) {
        if (dueDate == null) {
            throw new IllegalArgumentException("Choose a due date.");
        }
        this.dueDate = dueDate;
    }

    public double getEstimatedHours() {
        return estimatedHours;
    }

    /** Total study hours, more than 0 and at most 1000. */
    public final void setEstimatedHours(double estimatedHours) {
        if (!(estimatedHours > 0) || estimatedHours > 1000) {
            throw new IllegalArgumentException("Estimated hours must be more than 0 and at most 1000.");
        }
        this.estimatedHours = estimatedHours;
        if (completedHours >= estimatedHours - EPSILON) {
            isCompleted = true;
        }
    }

    public double getCompletedHours() {
        return completedHours;
    }

    public boolean isCompleted() {
        return isCompleted;
    }

    public Course getCourse() {
        return course;
    }

    void setCourse(Course course) {
        this.course = course;
    }

    /** All study blocks of this item, done and not done (read-only view). */
    public List<StudyBlock> getBlocks() {
        return Collections.unmodifiableList(blocks);
    }
}
