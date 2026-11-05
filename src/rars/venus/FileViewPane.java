package rars.venus;

import rars.Globals;
import rars.simulator.Simulator;

import javax.swing.*;
import java.awt.BorderLayout;
import java.awt.event.ActionEvent;

/**
 * One top-level tab of the editor: represents a single open file and contains
 * two sub-tabs: "Edit" (the file's editor) and "Memory" (the hosting place for
 * the shared memory/registers view).  The memory view (ExecutePane) is a
 * singleton shared by all files: it is moved into the sub-tab of the file
 * whose "Memory" tab is selected.
 *
 * @author Sanderson
 **/
public class FileViewPane extends JTabbedPane {

    public static final int EDIT_TAB = 0;
    public static final int MEMORY_TAB = 1;

    private final MainPane mainPane;
    private final EditPane editPane;
    // Hosts the shared ExecutePane while this file's "Memory" sub-tab is used.
    private final JPanel memoryPlaceholder = new JPanel(new BorderLayout());
    // Guards the "assemble on Memory-tab selection" listener against
    // re-entrancy (selection changes made while assembling).
    private boolean assemblingForMemoryView = false;

    /**
     * Create the per-file view with the "Edit" and "Memory" sub-tabs.
     *
     * @param mainPane  the main pane owning the shared memory view
     * @param editPane  the editor of this file
     */
    public FileViewPane(MainPane mainPane, EditPane editPane) {
        this.mainPane = mainPane;
        this.editPane = editPane;
        this.addTab("Edit", editPane);
        this.addTab("Memory", memoryPlaceholder);
        this.setToolTipTextAt(EDIT_TAB, "Text editor for composing RISCV programs.");
        this.setToolTipTextAt(MEMORY_TAB,
                "View memory and registers of the assembled program.  Switching to this tab re-assembles the current file.");
        this.addChangeListener(e -> {
            if (getSelectedIndex() == MEMORY_TAB) {
                onMemorySelected();
            }
        });
    }

    /**
     * The editor of this file.
     */
    public EditPane getEditPane() {
        return editPane;
    }

    /**
     * Whether the "Memory" sub-tab is currently selected.
     */
    public boolean isMemoryTabSelected() {
        return getSelectedIndex() == MEMORY_TAB;
    }

    /**
     * Select the "Edit" sub-tab.
     */
    public void showEdit() {
        setSelectedIndex(EDIT_TAB);
    }

    /**
     * Select the "Memory" sub-tab (which triggers the memory view and the
     * forced assembly, see {@link #onMemorySelected()}).
     */
    public void showMemory() {
        setSelectedIndex(MEMORY_TAB);
    }

    /*
     * Called when the "Memory" sub-tab becomes selected: the shared memory
     * view is hosted here and the current file is force-assembled, so the
     * view always corresponds to the current source text.  While a program
     * is executing (run session active), the live execution state is shown
     * instead and no assembly is performed.  If the assembly fails, the user
     * is returned to the "Edit" sub-tab (errors are in the Messages pane).
     */
    private void onMemorySelected() {
        // Host the shared memory view in this file's sub-tab.
        mainPane.attachMemoryView(this);
        if (assemblingForMemoryView) {
            return;
        }
        if (Simulator.getInstance().isSimulating()
                || mainPane.getEditTabbedPane().isRunSessionActive()) {
            return; // show the live execution state
        }
        assemblingForMemoryView = true;
        try {
            Globals.getGui().getRunAssembleAction().actionPerformed(
                    new ActionEvent(this, ActionEvent.ACTION_PERFORMED, "MemoryTab"));
            if (FileStatus.get() != FileStatus.RUNNABLE) {
                // nothing to show (no file open or assembly errors)
                setSelectedIndex(EDIT_TAB);
            } else {
                // Re-layout the internal windows so the Text Segment window
                // of the freshly assembled program is visible and large
                // enough.  Deferred until the tab activation layout pass is
                // done, so the final desktop size is used.
                SwingUtilities.invokeLater(() -> mainPane.getExecutePane().relayoutWindows());
            }
        } finally {
            assemblingForMemoryView = false;
        }
    }

    /*
     * Adds/removes the shared ExecutePane into/from this pane's placeholder.
     * Called by MainPane, which coordinates that only one file hosts the view.
     */
    void hostMemoryView(ExecutePane memoryView) {
        memoryPlaceholder.add(memoryView, BorderLayout.CENTER);
        memoryPlaceholder.revalidate();
        memoryPlaceholder.repaint();
    }

    void detachMemoryView() {
        memoryPlaceholder.removeAll();
        memoryPlaceholder.revalidate();
        memoryPlaceholder.repaint();
    }
}
