package studyplanner;

import studyplanner.storage.StorageManager;
import studyplanner.ui.MainDashboard;

import javax.swing.SwingUtilities;

/**
 * Starts the Study Planner and Exam Scheduler.
 *
 * <p>Packages (analysis document, section 4): {@code studyplanner.ui} is the user interface,
 * {@code studyplanner.engine} the business logic, {@code studyplanner.storage} the data storage and
 * {@code studyplanner.model} the entity classes. Data is kept in {@code data/study-planner.json} inside the folder the
 * program is started from (the project folder when run from NetBeans).
 */
public class Main {
    public static void main(String[] args) {
        SwingUtilities.invokeLater(() -> new MainDashboard(new StorageManager()).setVisible(true));
    }
}
