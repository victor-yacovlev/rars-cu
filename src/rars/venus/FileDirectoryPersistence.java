package rars.venus;

import java.util.prefs.Preferences;

/**
 * Persists the last-used directories for the file Open and Save dialogs
 * between application runs. Storage is java.util.prefs, the same mechanism
 * used by {@link UiLayoutPersistence} and {@link rars.Settings}.
 */
public final class FileDirectoryPersistence {

    private static final Preferences prefs = Preferences.userNodeForPackage(VenusUI.class);

    private static final String KEY_OPEN_DIRECTORY = "UiOpenDirectory";
    private static final String KEY_SAVE_DIRECTORY = "UiSaveDirectory";

    private FileDirectoryPersistence() {
    }

    /**
     * @return the persisted directory for the Open dialog, or null if none
     *         was ever saved.
     */
    public static String getOpenDirectory() {
        return prefs.get(KEY_OPEN_DIRECTORY, null);
    }

    /**
     * @return the persisted directory for the Save dialog, or null if none
     *         was ever saved.
     */
    public static String getSaveDirectory() {
        return prefs.get(KEY_SAVE_DIRECTORY, null);
    }

    /**
     * Stores the directory to be shown the next time the Open dialog is launched.
     *
     * @param directory pathname of the directory, or null to keep the previous value
     */
    public static void setOpenDirectory(String directory) {
        if (directory != null) {
            prefs.put(KEY_OPEN_DIRECTORY, directory);
        }
    }

    /**
     * Stores the directory to be shown the next time the Save dialog is launched.
     *
     * @param directory pathname of the directory, or null to keep the previous value
     */
    public static void setSaveDirectory(String directory) {
        if (directory != null) {
            prefs.put(KEY_SAVE_DIRECTORY, directory);
        }
    }
}