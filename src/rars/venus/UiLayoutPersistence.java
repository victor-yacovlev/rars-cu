package rars.venus;

import javax.swing.*;
import java.awt.*;
import java.util.prefs.Preferences;

/**
 * Persists the main window geometry and layout of individual UI elements
 * (split pane divider positions, Execute pane internal frames, selected
 * registers tab) between application runs. Storage is java.util.prefs,
 * the same mechanism used by {@link rars.Settings}.
 */
public final class UiLayoutPersistence {

    private static final Preferences prefs = Preferences.userNodeForPackage(VenusUI.class);

    private static final String KEY_X = "UiFrameX";
    private static final String KEY_Y = "UiFrameY";
    private static final String KEY_WIDTH = "UiFrameWidth";
    private static final String KEY_HEIGHT = "UiFrameHeight";
    private static final String KEY_MAXIMIZED = "UiFrameMaximized";
    private static final String KEY_MAIN_DIVIDER = "UiMainSplitterDivider";
    private static final String KEY_REGISTERS_DIVIDER = "UiRegistersSplitterDivider";
    private static final String KEY_REGISTERS_TAB = "UiRegistersTabIndex";
    private static final String KEY_INTERNAL_FRAME_PREFIX = "UiInternalFrame.";

    /** Minimum size (px) kept for each side of a split pane when restoring dividers. */
    private static final int MIN_SPLIT_PANE_SIZE = 80;
    /** Minimum size (px) kept for Execute pane internal frames when restoring bounds. */
    private static final int MIN_INTERNAL_FRAME_WIDTH = 200;
    private static final int MIN_INTERNAL_FRAME_HEIGHT = 100;

    private UiLayoutPersistence() {
    }

    /**
     * @return true if a main window geometry has been saved previously.
     */
    public static boolean hasSavedLayout() {
        return prefs.get(KEY_WIDTH, null) != null;
    }

    /**
     * Stores the current window geometry and layout of UI elements.
     * Called from a shutdown hook, so it must be quick and never fail.
     */
    public static void save(VenusUI ui) {
        try {
            Rectangle bounds = ui.getLastNormalBounds();
            if (bounds != null && bounds.width > 0 && bounds.height > 0) {
                prefs.putInt(KEY_X, bounds.x);
                prefs.putInt(KEY_Y, bounds.y);
                prefs.putInt(KEY_WIDTH, bounds.width);
                prefs.putInt(KEY_HEIGHT, bounds.height);
            }
            prefs.putBoolean(KEY_MAXIMIZED,
                    (ui.getExtendedState() & JFrame.MAXIMIZED_BOTH) != 0);
            prefs.putInt(KEY_MAIN_DIVIDER, ui.getMainSplitter().getDividerLocation());
            prefs.putInt(KEY_REGISTERS_DIVIDER, ui.getRegistersSplitter().getDividerLocation());
            prefs.putInt(KEY_REGISTERS_TAB, ui.getRegistersPane().getSelectedIndex());
            for (JInternalFrame frame : ui.getMainPane().getExecutePane().getAllFrames()) {
                Rectangle frameBounds = frame.getBounds();
                if (frameBounds.width > 0 && frameBounds.height > 0) {
                    prefs.put(KEY_INTERNAL_FRAME_PREFIX + frame.getTitle(),
                            frameBounds.x + "," + frameBounds.y + "," + frameBounds.width + "," + frameBounds.height);
                }
            }
            prefs.flush();
        } catch (Exception e) {
            // Layout persistence must never break shutdown.
        }
    }

    /**
     * Restores the main window geometry. Split panes, the registers tab and
     * the Execute pane frames are restored later, when the corresponding
     * components are laid out (see {@link #applyDividersAndTabs} and
     * ExecutePane#applyPersistedWindowBounds).
     *
     * @return true if a saved geometry was applied.
     */
    public static boolean applyFrameGeometry(VenusUI ui) {
        if (!hasSavedLayout()) {
            return false;
        }
        Rectangle bounds = new Rectangle(
                prefs.getInt(KEY_X, 0), prefs.getInt(KEY_Y, 0),
                prefs.getInt(KEY_WIDTH, 0), prefs.getInt(KEY_HEIGHT, 0));
        if (bounds.width <= 0 || bounds.height <= 0 || !isVisibleOnAnyScreen(bounds)) {
            return false;
        }
        ui.setBounds(bounds);
        if (prefs.getBoolean(KEY_MAXIMIZED, true)) {
            ui.setExtendedState(JFrame.MAXIMIZED_BOTH);
        }
        return true;
    }

    /**
     * Restores split pane divider positions and the selected registers tab.
     * Divider positions are clamped so that neither side of a split pane
     * becomes unusably small, even if the saved layout came from a screen
     * or window of a different size. Must be invoked after the frame has
     * been laid out (invokeLater).
     */
    public static void applyDividersAndTabs(VenusUI ui) {
        applyDivider(ui.getMainSplitter(), prefs.getInt(KEY_MAIN_DIVIDER, -1));
        applyDivider(ui.getRegistersSplitter(), prefs.getInt(KEY_REGISTERS_DIVIDER, -1));
        int tab = prefs.getInt(KEY_REGISTERS_TAB, -1);
        if (tab >= 0 && tab < ui.getRegistersPane().getTabCount()) {
            ui.getRegistersPane().setSelectedIndex(tab);
        }
    }

    private static void applyDivider(JSplitPane pane, int divider) {
        if (divider <= 0) {
            return;
        }
        int size = (pane.getOrientation() == JSplitPane.VERTICAL_SPLIT) ? pane.getHeight() : pane.getWidth();
        if (size <= 0) {
            return;
        }
        int min = Math.max(MIN_SPLIT_PANE_SIZE, pane.getMinimumDividerLocation());
        int max = Math.max(min, size - MIN_SPLIT_PANE_SIZE - pane.getDividerSize());
        int clamped = Math.max(min, Math.min(divider, max));
        if (clamped < size) {
            pane.setDividerLocation(clamped);
        }
    }

    /**
     * @return saved bounds of an Execute pane internal frame (identified by
     *         its title), clamped to remain visible and reasonably sized inside
     *         the given desktop bounds, or null if not saved.
     */
    public static Rectangle getSavedInternalFrameBounds(String title, Dimension desktopSize) {
        String value = prefs.get(KEY_INTERNAL_FRAME_PREFIX + title, null);
        if (value == null) {
            return null;
        }
        try {
            String[] parts = value.split(",");
            if (parts.length != 4) {
                return null;
            }
            Rectangle bounds = new Rectangle(Integer.parseInt(parts[0]), Integer.parseInt(parts[1]),
                    Integer.parseInt(parts[2]), Integer.parseInt(parts[3]));
            int width = Math.min(Math.max(bounds.width, MIN_INTERNAL_FRAME_WIDTH),
                    Math.max(desktopSize.width, MIN_INTERNAL_FRAME_WIDTH));
            int height = Math.min(Math.max(bounds.height, MIN_INTERNAL_FRAME_HEIGHT),
                    Math.max(desktopSize.height, MIN_INTERNAL_FRAME_HEIGHT));
            int x = Math.max(0, Math.min(bounds.x, Math.max(0, desktopSize.width - MIN_INTERNAL_FRAME_WIDTH)));
            int y = Math.max(0, Math.min(bounds.y, Math.max(0, desktopSize.height - MIN_INTERNAL_FRAME_HEIGHT)));
            return new Rectangle(x, y, width, height);
        } catch (NumberFormatException e) {
            return null;
        }
    }

    private static boolean isVisibleOnAnyScreen(Rectangle bounds) {
        for (GraphicsDevice device : GraphicsEnvironment.getLocalGraphicsEnvironment().getScreenDevices()) {
            if (device.getDefaultConfiguration().getBounds().intersects(bounds)) {
                return true;
            }
        }
        return false;
    }
}
