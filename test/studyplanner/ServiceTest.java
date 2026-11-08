package studyplanner;

import java.io.File;
import java.nio.file.Files;
import java.time.*;
import java.util.*;

/**
 * Automated checks of the service and persistence layers (no GUI, no test framework).
 * Compile together with Main.java, then run:  java studyplanner.ServiceTest
 */
public class ServiceTest {
    static int passed = 0, failed = 0;
    // Wednesday 2026-11-04, 09:00 - a fixed "now" so results do not depend on the day the test is run
    static final LocalDateTime NOW = LocalDateTime.of(2026, 11, 4, 9, 0);

    static void check(boolean ok, String name) {
        if (ok) { passed++; System.out.println("  PASS  " + name); }
        else { failed++; System.out.println("  FAIL  " + name); }
    }

    static PlannerData data() {
        PlannerData d = new PlannerData();
        d.courses.add(new Course("CS201", "Data Structures", "Dr. A"));
        d.courses.add(new Course("MA101", "Calculus", "Dr. B"));
        return d;
    }

    static Assessment add(PlannerData d, String id, String course, String type, int daysAhead, String diff) {
        Assessment a = new Assessment(id, course, id, type, NOW.toLocalDate().plusDays(daysAhead), 20, diff);
        d.assessments.add(a);
        return a;
    }

    static List<StudySession> sessionsOf(PlannerData d, String id) {
        List<StudySession> out = new ArrayList<>();
        for (StudySession s : d.sessions) if (s.assessmentId.equals(id)) out.add(s);
        return out;
    }

    public static void main(String[] args) throws Exception {
        System.out.println("Study plan generation");
        PlannerData d = data();
        PlannerService svc = new PlannerService(d);
        Assessment mid = add(d, "Midterm", "CS201", "Exam", 14, "Hard");
        check(svc.generate(mid, NOW) == 5, "a Hard assessment gets 5 sessions");
        boolean weekdays = true, beforeDeadline = true, slotOk = true;
        for (StudySession s : sessionsOf(d, "Midterm")) {
            if (s.date.getDayOfWeek() == DayOfWeek.SATURDAY || s.date.getDayOfWeek() == DayOfWeek.SUNDAY) weekdays = false;
            if (!s.date.isBefore(mid.deadline)) beforeDeadline = false;
            if (s.start.getHour() < 17 || s.start.getHour() > 19 || !s.end.equals(s.start.plusHours(1))) slotOk = false;
        }
        check(weekdays, "sessions are only on weekdays");
        check(beforeDeadline, "no session on or after the deadline day");
        check(slotOk, "sessions are one-hour slots between 17:00 and 20:00");
        List<StudySession> ms = sessionsOf(d, "Midterm");
        long span = Duration.between(ms.get(0).date.atStartOfDay(), ms.get(ms.size() - 1).date.atStartOfDay()).toDays();
        check(span >= 8, "sessions are spread over the available days (span " + span + " days)");
        check(svc.generate(mid, NOW) == 0 && sessionsOf(d, "Midterm").size() == 5, "pressing Generate again adds no duplicates");

        Assessment quiz = add(d, "Quiz", "MA101", "Quiz", 3, "Medium");
        check(svc.generate(quiz, NOW) == 3, "an earlier deadline still gets its 3 sessions (no starvation)");
        Set<String> slots = new HashSet<>();
        boolean clash = false;
        for (StudySession s : d.sessions) if (!slots.add(s.date + " " + s.start)) clash = true;
        check(!clash, "two sessions never share the same date and time");

        System.out.println("Class times and edge cases");
        PlannerData d2 = data();
        PlannerService s2 = new PlannerService(d2);
        for (DayOfWeek day : new DayOfWeek[]{DayOfWeek.MONDAY, DayOfWeek.TUESDAY, DayOfWeek.WEDNESDAY, DayOfWeek.THURSDAY, DayOfWeek.FRIDAY}) {
            d2.meetings.add(new ClassMeeting("CS201", day.name(), LocalTime.of(17, 0), LocalTime.of(18, 0)));
        }
        Assessment a2 = add(d2, "Project", "CS201", "Assignment", 10, "Medium");
        s2.generate(a2, NOW);
        boolean avoided = true;
        for (StudySession s : d2.sessions) if (s.start.equals(LocalTime.of(17, 0))) avoided = false;
        check(!d2.sessions.isEmpty() && avoided, "the 17:00 slot is avoided when a class runs then; other slots are used");
        check(s2.conflicts().isEmpty(), "generated sessions never produce class conflicts");

        PlannerData d3 = data();
        PlannerService s3 = new PlannerService(d3);
        check(s3.generate(add(d3, "Late", "CS201", "Quiz", -2, "Hard"), NOW) == 0, "a deadline in the past gets no sessions");
        check(s3.generate(add(d3, "Today", "CS201", "Quiz", 0, "Hard"), NOW) == 0, "a deadline today gets no sessions");
        check(s3.generate(add(d3, "Easy", "CS201", "Quiz", 10, "Easy"), NOW) == 2, "an Easy assessment gets 2 sessions");

        PlannerData d4 = data();
        PlannerService s4 = new PlannerService(d4);
        Assessment a4 = add(d4, "Report", "CS201", "Assignment", 12, "Medium");
        d4.sessions.add(new StudySession("old", "Report", "CS201", NOW.toLocalDate().minusDays(2), LocalTime.of(17, 0), LocalTime.of(18, 0)));
        check(s4.generate(a4, NOW) == 3, "a missed (past, unfinished) session is planned again");
        StudySession done = sessionsOf(d4, "Report").get(sessionsOf(d4, "Report").size() - 1);
        done.completed = true;
        check(s4.generate(a4, NOW) == 0, "a completed session counts towards the target (still 3 in total)");
        d4.sessions.remove(sessionsOf(d4, "Report").get(1));   // one future pending session disappears
        check(s4.generate(a4, NOW) == 1, "only the shortfall is added when a session is missing");

        System.out.println("Conflict detection");
        PlannerData d5 = data();
        PlannerService s5 = new PlannerService(d5);
        add(d5, "Exam A", "CS201", "Exam", 9, "Hard");
        add(d5, "Exam B", "MA101", "Exam", 9, "Hard");
        add(d5, "Quiz C", "MA101", "Quiz", 9, "Easy");
        check(s5.conflicts().size() == 1 && s5.conflicts().get(0).startsWith("Exam conflict"), "two exams on the same date are flagged (a quiz is not)");
        d5.meetings.add(new ClassMeeting("CS201", "THURSDAY", LocalTime.of(16, 30), LocalTime.of(17, 30)));
        d5.sessions.add(new StudySession("x", "Exam A", "CS201", LocalDate.of(2026, 11, 5), LocalTime.of(17, 0), LocalTime.of(18, 0)));
        boolean classFlag = false;
        for (String w : s5.conflicts()) if (w.startsWith("Class conflict")) classFlag = true;
        check(classFlag, "a session overlapping a class is flagged");
        for (int h = 18; h < 22; h++) {
            d5.sessions.add(new StudySession("o" + h, "Exam B", "MA101", LocalDate.of(2026, 11, 6), LocalTime.of(h, 0), LocalTime.of(h + 1, 0)));
        }
        boolean overloaded = false;
        for (String w : s5.conflicts()) if (w.startsWith("Overloaded day") && w.contains("2026-11-06")) overloaded = true;
        check(overloaded, "a day with more than 3 pending sessions is flagged");

        System.out.println("Persistence");
        File f = File.createTempFile("planner", ".ser");
        f.delete();
        DataStore store = new DataStore(f);
        check(store.load().courses.isEmpty() && store.loadWarning == null, "a missing file gives empty data without a warning");
        PlannerData d6 = data();
        add(d6, "Final", "CS201", "Exam", 20, "Hard");
        new PlannerService(d6).generate(d6.assessments.get(0), NOW);
        d6.sessions.get(0).completed = true;
        d6.meetings.add(new ClassMeeting("CS201", "MONDAY", LocalTime.of(9, 0), LocalTime.of(10, 0)));
        store.save(d6);
        PlannerData back = new DataStore(f).load();
        check(back.courses.size() == 2 && back.assessments.size() == 1 && back.sessions.size() == 5 && back.meetings.size() == 1, "save and load keep all objects");
        check(back.sessions.get(0).completed && !back.sessions.get(1).completed, "the completed flag is kept");
        Files.write(f.toPath(), "not a serialized file".getBytes());
        DataStore broken = new DataStore(f);
        PlannerData empty = broken.load();
        File bad = new File(f.getPath() + ".corrupt");
        check(empty.courses.isEmpty() && broken.loadWarning != null, "a damaged file gives empty data and a warning");
        check(bad.exists() && !f.exists(), "the damaged file is kept as .corrupt, not overwritten");
        f.delete(); bad.delete();

        System.out.println();
        System.out.println(passed + " passed, " + failed + " failed");
        if (failed > 0) System.exit(1);
    }
}
