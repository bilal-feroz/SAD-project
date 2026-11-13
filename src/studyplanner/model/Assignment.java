package studyplanner.model;

import java.time.LocalDate;

/** An assignment: work that is handed in at the end of its due date. */
public class Assignment extends AcademicItem {
    private int complexityLevel;   // 1 (simple) to 5 (very complex)

    public Assignment(String id, String title, LocalDate dueDate, double estimatedHours, int complexityLevel) {
        super(id, title, dueDate, estimatedHours);
        setComplexityLevel(complexityLevel);
    }

    @Override
    public String getItemCategory() {
        return "Assignment";
    }

    /** An assignment can still be worked on during its due date, so the hours are spread up to and including it. */
    @Override
    public double calculateDailyHours(LocalDate today) {
        return spreadUntil(today, getDueDate());
    }

    public int getComplexityLevel() {
        return complexityLevel;
    }

    public final void setComplexityLevel(int complexityLevel) {
        if (complexityLevel < 1 || complexityLevel > 5) {
            throw new IllegalArgumentException("Complexity must be from 1 to 5.");
        }
        this.complexityLevel = complexityLevel;
    }
}
