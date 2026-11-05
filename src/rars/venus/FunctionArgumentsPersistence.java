package rars.venus;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.prefs.Preferences;

/**
 * Persists the last-used argument values of the function-run argument dialog
 * (see rars.venus.run.FunctionRunSupport) between application runs. Storage
 * is java.util.prefs, the same mechanism used by {@link UiLayoutPersistence}
 * and {@link FileDirectoryPersistence}.
 *
 * The key is the pathname of the open source file, so each file keeps its
 * own argument history. Both dialog kinds are remembered: the individual
 * values of the named-parameters dialog (when a C signature is present) and
 * the raw input line of the free-form dialog (when it is not).
 */
public final class FunctionArgumentsPersistence {

    private static final Preferences prefs = Preferences.userNodeForPackage(VenusUI.class);

    private static final String KEY_NAMED_PREFIX = "FunctionRunNamedArgs.";
    private static final String KEY_FREEFORM_PREFIX = "FunctionRunFreeArgs.";

    // java.util.prefs limits key length, so long pathnames are stored as a hash.
    private static final int MAX_PATH_KEY_LENGTH =
            Preferences.MAX_KEY_LENGTH - Math.max(KEY_NAMED_PREFIX.length(), KEY_FREEFORM_PREFIX.length());

    private FunctionArgumentsPersistence() {
    }

    /**
     * @return the values entered in the named-parameters dialog on the last
     *         run for the given file, or null if none was ever saved (or the
     *         stored value is corrupted).
     */
    public static long[] getLastNamedArguments(String fileKey) {
        if (fileKey == null) {
            return null;
        }
        String stored = prefs.get(KEY_NAMED_PREFIX + prefsKeySuffix(fileKey), null);
        if (stored == null || stored.isEmpty()) {
            return null;
        }
        String[] tokens = stored.trim().split("\\s+");
        long[] values = new long[tokens.length];
        for (int i = 0; i < tokens.length; i++) {
            try {
                values[i] = Long.decode(tokens[i]);
            } catch (NumberFormatException e) {
                return null; // corrupted entry: ignore the history
            }
        }
        return values;
    }

    /**
     * Stores the argument values used in the named-parameters dialog.
     *
     * @param fileKey pathname of the open file, or null to skip storing
     */
    public static void saveNamedArguments(String fileKey, long[] values) {
        if (fileKey == null || values == null) {
            return;
        }
        StringBuilder stored = new StringBuilder();
        for (int i = 0; i < values.length; i++) {
            if (i > 0) {
                stored.append(' ');
            }
            stored.append(values[i]);
        }
        prefs.put(KEY_NAMED_PREFIX + prefsKeySuffix(fileKey), stored.toString());
    }

    /**
     * @return the free-form argument input line from the last run for the
     *         given file, or an empty string if none was ever saved.
     */
    public static String getLastFreeFormLine(String fileKey) {
        if (fileKey == null) {
            return "";
        }
        return prefs.get(KEY_FREEFORM_PREFIX + prefsKeySuffix(fileKey), "");
    }

    /**
     * Stores the free-form argument input line.
     *
     * @param fileKey pathname of the open file, or null to skip storing
     */
    public static void saveFreeFormLine(String fileKey, String line) {
        if (fileKey == null || line == null) {
            return;
        }
        prefs.put(KEY_FREEFORM_PREFIX + prefsKeySuffix(fileKey), line);
    }

    private static String prefsKeySuffix(String fileKey) {
        if (fileKey.length() <= MAX_PATH_KEY_LENGTH) {
            return fileKey;
        }
        // 16 bytes of SHA-256 as hex (33 chars total): fits the prefs key limit.
        try {
            byte[] hash = MessageDigest.getInstance("SHA-256")
                    .digest(fileKey.getBytes(StandardCharsets.UTF_8));
            StringBuilder hex = new StringBuilder("h");
            for (int i = 0; i < 16; i++) {
                hex.append(String.format("%02x", hash[i]));
            }
            return hex.toString();
        } catch (NoSuchAlgorithmException e) {
            return "h" + Integer.toHexString(fileKey.hashCode());
        }
    }
}
