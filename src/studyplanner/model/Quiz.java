package studyplanner.model;

import java.time.LocalDate;

/** A quiz: taken in class on its due date. */
public class Quiz extends AcademicItem {
    private int chapterNumber;   // the chapter the quiz covers

    public Quiz(String id, String title, LocalDate dueDate, double estimatedHours, int chapterNumber) {
        super(id, title, dueDate, estimatedHours);
        setChapterNumber(chapterNumber);
    }

    @Override
    public String getItemCategory() {
        return "Quiz";
    }

    /** A quiz is taken on its due date, so the hours are spread over the days before it. */
    @Override
    public double calculateDailyHours(LocalDate today) {
        return spreadUntil(today, getDueDate().minusDays(1));
    }

    public int getChapterNumber() {
        return chapterNumber;
    }

    public final void setChapterNumber(int chapterNumber) {
        if (chapterNumber < 1 || chapterNumber > 999) {
            throw new IllegalArgumentException("Chapter number must be from 1 to 999.");
        }
        this.chapterNumber = chapterNumber;
    }
}
