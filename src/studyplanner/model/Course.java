package studyplanner.model;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * A semester course (entity class).
 *
 * <p>A course owns its academic items: in the class diagram this is a composition, Course 1 --* AcademicItem,
 * so deleting a course also deletes its assignments, quizzes and exams.
 */
public class Course {
    private String courseCode;
    private String courseName;
    private final List<AcademicItem> items = new ArrayList<>();

    public Course(String courseCode, String courseName) {
        setCourseCode(courseCode);
        setCourseName(courseName);
    }

    /** Adds an assignment, quiz or exam to this course and makes this course its parent. */
    public void addAcademicItem(AcademicItem item) {
        if (item.getCourse() != null && item.getCourse() != this) {
            item.getCourse().removeAcademicItem(item);
        }
        item.setCourse(this);
        if (!items.contains(item)) {
            items.add(item);
        }
    }

    /** Removes an item (and with it its study blocks) from this course. */
    public void removeAcademicItem(AcademicItem item) {
        if (items.remove(item)) {
            item.setCourse(null);
        }
    }

    /** Study hours still to do for all items of this course. */
    public double getRemainingCourseHours() {
        double sum = 0;
        for (AcademicItem item : items) {
            sum += item.getRemainingHours();
        }
        return sum;
    }

    /** Number of items of this course that are completed. */
    public int getCompletedTaskCount() {
        int n = 0;
        for (AcademicItem item : items) {
            if (item.isCompleted()) {
                n++;
            }
        }
        return n;
    }

    public String getCourseCode() {
        return courseCode;
    }

    /** Course code such as "CS302"; it may not be empty. */
    public final void setCourseCode(String courseCode) {
        if (courseCode == null || courseCode.trim().isEmpty()) {
            throw new IllegalArgumentException("Enter a course code.");
        }
        this.courseCode = courseCode.trim();
    }

    public String getCourseName() {
        return courseName;
    }

    /** Course title such as "Object-Oriented Analysis and Design"; it may not be empty. */
    public final void setCourseName(String courseName) {
        if (courseName == null || courseName.trim().isEmpty()) {
            throw new IllegalArgumentException("Enter a course title.");
        }
        this.courseName = courseName.trim();
    }

    /** The items of this course, in the order they were added (read-only view). */
    public List<AcademicItem> getItems() {
        return Collections.unmodifiableList(items);
    }

    @Override
    public String toString() {
        return courseCode + " - " + courseName;
    }
}
