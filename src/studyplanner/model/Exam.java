package studyplanner.model;

import java.time.LocalDate;

/** An exam: sat on its due date. Two exams on the same day are reported as a conflict. */
public class Exam extends AcademicItem {
    private int examDurationMinutes;
    private double weightPercentage;   // share of the course grade, 0 to 100

    public Exam(String id, String title, LocalDate dueDate, double estimatedHours,
                int examDurationMinutes, double weightPercentage) {
        super(id, title, dueDate, estimatedHours);
        setExamDurationMinutes(examDurationMinutes);
        setWeightPercentage(weightPercentage);
    }

    @Override
    public String getItemCategory() {
        return "Exam";
    }

    /** An exam is sat on its due date, so the hours are spread over the days before it. */
    @Override
    public double calculateDailyHours(LocalDate today) {
        return spreadUntil(today, getDueDate().minusDays(1));
    }

    public int getExamDurationMinutes() {
        return examDurationMinutes;
    }

    public final void setExamDurationMinutes(int examDurationMinutes) {
        if (examDurationMinutes < 1 || examDurationMinutes > 600) {
            throw new IllegalArgumentException("Exam duration must be from 1 to 600 minutes.");
        }
        this.examDurationMinutes = examDurationMinutes;
    }

    public double getWeightPercentage() {
        return weightPercentage;
    }

    public final void setWeightPercentage(double weightPercentage) {
        if (!(weightPercentage >= 0) || weightPercentage > 100) {
            throw new IllegalArgumentException("Exam weight must be from 0 to 100 %.");
        }
        this.weightPercentage = weightPercentage;
    }
}
