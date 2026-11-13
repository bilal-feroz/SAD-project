package studyplanner;

import studyplanner.engine.ScheduleEngine;
import studyplanner.engine.ScheduleEngine.ScheduleResult;
import studyplanner.model.AcademicItem;
import studyplanner.model.Assignment;
import studyplanner.model.Course;
import studyplanner.model.Exam;
import studyplanner.model.Quiz;
import studyplanner.model.StudyBlock;
import studyplanner.storage.StorageManager;

import java.io.File;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;

/**
 * Automated checks of the model, the schedule engine and the storage (no GUI, no test framework).
 * Run: java -cp out studyplanner.PlannerTest
 */
public class PlannerTest {
    static int passed = 0, failed = 0;
    /** A fixed Monday, so the results do not depend on the day the tests are run. */
    static final LocalDate TODAY = LocalDate.of(2026, 11, 16);
    static final double EPS = 1e-6;

    static void check(boolean ok, String name) {
        if (ok) {
            passed++;
            System.out.println("  PASS  " + name);
        } else {
            failed++;
            System.out.println("  FAIL  " + name);
        }
    }

    static boolean near(double a, double b) {
        return Math.abs(a - b) < 1e-6;
    }

    static List<StudyBlock> pending(AcademicItem item) {
        List<StudyBlock> out = new ArrayList<>();
        for (StudyBlock b : item.getBlocks()) if (!b.isDone()) out.add(b);
        return out;
    }

    static double sum(List<StudyBlock> blocks) {
        double s = 0;
        for (StudyBlock b : blocks) s += b.getAllocatedHours();
        return s;
    }

    static File tempFile() throws Exception {
        File f = File.createTempFile("planner", ".json");
        f.delete();
        return f;
    }

    public static void main(String[] args) throws Exception {
        File file = tempFile();
        StorageManager storage = new StorageManager(file.getPath());
        ScheduleEngine engine = new ScheduleEngine(storage);
        Course cs = new Course("CS302", "Object-Oriented Analysis and Design");
        storage.addCourse(cs);

        System.out.println("Daily hours: remaining hours / days left (FR2)");
        Assignment a = new Assignment(null, "Project report", TODAY.plusDays(4), 10, 3);
        Quiz q = new Quiz(null, "Quiz 3", TODAY.plusDays(4), 6, 4);
        Exam x = new Exam(null, "Midterm", TODAY.plusDays(4), 8, 120, 30);
        check(near(a.calculateDailyHours(TODAY), 2.0), "an assignment spreads 10 h over 5 days incl. the due date = 2.0 h/day");
        check(near(q.calculateDailyHours(TODAY), 1.5), "a quiz spreads 6 h over the 4 days before it = 1.5 h/day");
        check(near(x.calculateDailyHours(TODAY), 2.0), "an exam spreads 8 h over the 4 days before it = 2.0 h/day");
        check(a.getItemCategory().equals("Assignment") && q.getItemCategory().equals("Quiz") && x.getItemCategory().equals("Exam"),
                "each subclass names its own category (polymorphism)");

        cs.addAcademicItem(a);
        cs.addAcademicItem(q);
        cs.addAcademicItem(x);
        check(a.getCourse() == cs && cs.getItems().size() == 3, "items know their parent course (composition)");
        ScheduleResult r = engine.generateSchedule(5.0, TODAY);
        check(r.getStudyBlocks().size() == 13, "13 blocks are made: 5 + 4 + 4 (got " + r.getStudyBlocks().size() + ")");
        check(pending(a).size() == 5 && near(sum(pending(a)), 10), "assignment: 5 daily blocks adding up to 10 h");
        boolean examBeforeDay = true, oneBlockPerDay = true;
        for (StudyBlock b : pending(x)) if (!b.getAssignedDate().isBefore(x.getDueDate())) examBeforeDay = false;
        List<LocalDate> seen = new ArrayList<>();
        for (StudyBlock b : pending(a)) {
            if (seen.contains(b.getAssignedDate())) oneBlockPerDay = false;
            seen.add(b.getAssignedDate());
        }
        check(examBeforeDay, "no exam study block on or after the exam day");
        check(oneBlockPerDay && pending(a).get(0).getAssignedDate().equals(TODAY), "one block per calendar day, starting today");
        check(r.getWarnings().size() == 4 && r.getWarnings().get(0).startsWith("Overload Warning: Nov 16 requires 5.5 hrs"),
                "5.5 h on Mon-Thu is over the 5.0 h limit, Friday's 2 h is not (FR3): " + r.getWarnings().size() + " warnings");

        System.out.println("Progress (FR4, sequence 2)");
        StudyBlock first = pending(a).get(0);
        check(engine.markBlockComplete(first.getBlockId()), "markBlockComplete saves and returns true");
        check(first.isDone() && near(a.getCompletedHours(), 2) && near(a.getRemainingHours(), 8),
                "ticking a 2 h block takes 2 h off the task (10 -> 8 remaining)");
        check(file.exists(), "the progress was written to the JSON file");
        engine.generateSchedule(5.0, TODAY);
        check(pending(a).size() == 4 && pending(a).get(0).getAssignedDate().equals(TODAY.plusDays(1)) && near(sum(pending(a)), 8),
                "planning again keeps the done block and spreads 8 h from tomorrow (today is already done)");
        check(a.getBlocks().size() == 5, "the done block is kept as history");
        engine.reopenBlock(first.getBlockId());
        check(!first.isDone() && near(a.getRemainingHours(), 10), "unticking gives the hours back");
        engine.markBlockComplete(first.getBlockId());
        for (StudyBlock b : pending(a)) engine.markBlockComplete(b.getBlockId());
        check(a.isCompleted() && near(a.getRemainingHours(), 0), "ticking every block completes the assignment");
        check(cs.getCompletedTaskCount() == 1, "the course counts 1 completed task");
        check(near(cs.getRemainingCourseHours(), 6 + 8), "course remaining hours = quiz 6 + exam 8");
        engine.generateSchedule(5.0, TODAY);
        check(pending(a).isEmpty(), "a completed task gets no new blocks");

        q.markCompleted();
        engine.generateSchedule(5.0, TODAY);
        check(pending(q).isEmpty() && near(q.getRemainingHours(), 0), "marking a task done early removes its open blocks");
        q.reopen();
        engine.generateSchedule(5.0, TODAY);
        check(!q.isCompleted() && near(sum(pending(q)), 6), "reopening it plans its 6 h again");

        System.out.println("Edge cases");
        Course ma = new Course("MA201", "Linear Algebra");
        storage.addCourse(ma);
        Quiz qToday = new Quiz(null, "Pop quiz", TODAY, 2, 1);
        Assignment aToday = new Assignment(null, "Worksheet", TODAY, 3, 1);
        Exam past = new Exam(null, "Old exam", TODAY.minusDays(3), 5, 60, 10);
        ma.addAcademicItem(qToday);
        ma.addAcademicItem(aToday);
        ma.addAcademicItem(past);
        engine.generateSchedule(5.0, TODAY);
        check(pending(qToday).isEmpty(), "a quiz that is today gets no study block (no days before it)");
        check(pending(aToday).size() == 1 && near(sum(pending(aToday)), 3), "an assignment due today gets all its hours today");
        check(pending(past).isEmpty() && past.isOverdue(TODAY), "an overdue exam gets no blocks and counts as overdue");
        ma.removeAcademicItem(qToday);
        ma.removeAcademicItem(aToday);
        ma.removeAcademicItem(past);
        check(ma.getItems().isEmpty() && qToday.getCourse() == null, "removing items from a course works");

        System.out.println("Conflicts (FR3)");
        Exam other = new Exam(null, "Final", TODAY.plusDays(4), 4, 90, 40);
        Assignment sameDay = new Assignment(null, "Lab", TODAY.plusDays(4), 1, 1);
        ma.addAcademicItem(other);
        ma.addAcademicItem(sameDay);
        r = engine.generateSchedule(20.0, TODAY);
        check(r.getWarnings().size() == 1 && r.getWarnings().get(0).startsWith("Conflict Warning: 2 Exams scheduled on 2026-11-20"),
                "two exams on one date are flagged, an assignment on that date is not: " + r.getWarnings());
        r = engine.generateSchedule(2.0, TODAY);
        int overloads = 0;
        for (String w : r.getWarnings()) if (w.startsWith("Overload Warning")) overloads++;
        check(overloads == 4, "with a 2 h limit Mon-Thu are overloaded; Friday's early-done work does not count (got " + overloads + ")");
        check(r.getWarnings().get(1).equals("Overload Warning: Nov 16 requires 6.7 hrs, passing your 2.0 hr daily limit"),
                "warning text matches the analysis document: " + r.getWarnings().get(1));

        System.out.println("Validation");
        check(throwsError(() -> new Course(" ", "x")), "a course needs a code");
        check(throwsError(() -> new Assignment(null, "t", TODAY, 0, 1)), "estimated hours must be above 0");
        check(throwsError(() -> new Assignment(null, "t", TODAY, 1, 6)), "assignment complexity is 1 to 5");
        check(throwsError(() -> new Exam(null, "t", TODAY, 1, 60, 120)), "exam weight is 0 to 100 %");
        check(throwsError(() -> storage.addCourse(new Course("cs302", "Duplicate"))), "course codes are unique (ignoring case)");
        check(storage.findCourse("cs302") == cs, "a course can be found by its code");

        System.out.println("Saving and loading (FR5)");
        a.setTitle("Report \"draft\" \\ caf\u00e9 \u2013 part 1");
        storage.setDailyLimit(4.5);
        check(storage.saveItem(a), "saveItem confirms the save");
        StorageManager back = new StorageManager(file.getPath());
        List<Course> loaded = back.loadAll();
        check(back.getLoadWarning() == null && loaded.size() == 2, "both courses are loaded");
        Course cs2 = back.findCourse("CS302");
        AcademicItem a2 = cs2.getItems().get(0), q2 = cs2.getItems().get(1), x2 = cs2.getItems().get(2);
        check(a2 instanceof Assignment && q2 instanceof Quiz && x2 instanceof Exam, "each item comes back as the right subclass");
        check(a2.getTitle().equals(a.getTitle()), "quotes, backslashes and accents in titles survive");
        check(((Assignment) a2).getComplexityLevel() == 3 && ((Quiz) q2).getChapterNumber() == 4
                        && ((Exam) x2).getExamDurationMinutes() == 120 && near(((Exam) x2).getWeightPercentage(), 30),
                "subclass fields are kept");
        check(a2.isCompleted() && near(a2.getCompletedHours(), 10) && a2.getBlocks().size() == a.getBlocks().size(),
                "progress and study blocks are kept");
        check(near(back.getDailyLimit(), 4.5), "the daily limit is kept");
        check(a2.getCourse() == cs2 && a2.getBlocks().get(0).getParentItem() == a2, "links between course, item and block are rebuilt");

        File missing = tempFile();
        StorageManager none = new StorageManager(missing.getPath());
        check(none.loadAll().isEmpty() && none.getLoadWarning() == null, "a missing file gives empty data without a warning");
        Files.write(file.toPath(), "{ not json".getBytes(StandardCharsets.UTF_8));
        StorageManager broken = new StorageManager(file.getPath());
        File corrupt = new File(file.getPath() + ".corrupt");
        check(broken.loadAll().isEmpty() && broken.getLoadWarning() != null, "a damaged file gives empty data and a warning");
        check(corrupt.exists() && !file.exists(), "the damaged file is kept as .corrupt");
        corrupt.delete();

        System.out.println("Performance (NFR: under one second for 10 courses and 100 items)");
        File big = tempFile();
        StorageManager bs = new StorageManager(big.getPath());
        for (int c = 0; c < 10; c++) {
            Course course = new Course("C" + c, "Course " + c);
            bs.addCourse(course);
            for (int i = 0; i < 10; i++) {
                LocalDate due = TODAY.plusDays(5 + (c * 10 + i) % 60);
                course.addAcademicItem(i % 3 == 0 ? new Exam(null, "E" + i, due, 20, 120, 25)
                        : i % 3 == 1 ? new Quiz(null, "Q" + i, due, 6, i) : new Assignment(null, "A" + i, due, 12, 2));
            }
        }
        long start = System.nanoTime();
        ScheduleResult br = new ScheduleEngine(bs).generateSchedule(5.0, TODAY);
        bs.saveNow();
        long ms = (System.nanoTime() - start) / 1_000_000;
        check(ms < 1000 && !br.getStudyBlocks().isEmpty(), "planning, checking and saving 100 items took " + ms + " ms");
        big.delete();
        file.delete();

        System.out.println();
        System.out.println(passed + " passed, " + failed + " failed");
        if (failed > 0) System.exit(1);
    }

    static boolean throwsError(Runnable r) {
        try {
            r.run();
            return false;
        } catch (IllegalArgumentException e) {
            return true;
        }
    }
}
