package studyplanner.model;

import java.time.LocalDate;
import java.util.UUID;

/**
 * One calendar day's share of study hours for one academic item (no clock times).
 * Ticking a block off takes its hours off the parent item's remaining hours.
 */
public final class StudyBlock {
    private final String blockId;
    private final LocalDate assignedDate;
    private final double allocatedHours;
    private boolean isDone;
    private final AcademicItem parentItem;

    /** A new, not yet done block. */
    public StudyBlock(LocalDate assignedDate, double allocatedHours, AcademicItem parentItem) {
        this(null, assignedDate, allocatedHours, false, parentItem);
    }

    /** A block with a known id and state (used when loading saved data). */
    public StudyBlock(String blockId, LocalDate assignedDate, double allocatedHours, boolean isDone, AcademicItem parentItem) {
        if (assignedDate == null || parentItem == null || !(allocatedHours > 0)) {
            throw new IllegalArgumentException("A study block needs a date, hours above 0 and a parent item.");
        }
        this.blockId = blockId == null || blockId.isEmpty() ? UUID.randomUUID().toString() : blockId;
        this.assignedDate = assignedDate;
        this.allocatedHours = allocatedHours;
        this.isDone = isDone;
        this.parentItem = parentItem;
        parentItem.attachBlock(this);
    }

    /** Ticks the block off: it becomes done and its hours are taken off the parent item. */
    public void markBlockDone() {
        if (!isDone) {
            isDone = true;
            parentItem.addCompletedHours(allocatedHours);
        }
    }

    /** Unticks the block (undo): its hours count as remaining work again. */
    public void markBlockNotDone() {
        if (isDone) {
            isDone = false;
            parentItem.addCompletedHours(-allocatedHours);
        }
    }

    public String getBlockId() {
        return blockId;
    }

    public LocalDate getAssignedDate() {
        return assignedDate;
    }

    public double getAllocatedHours() {
        return allocatedHours;
    }

    public boolean isDone() {
        return isDone;
    }

    public AcademicItem getParentItem() {
        return parentItem;
    }
}
