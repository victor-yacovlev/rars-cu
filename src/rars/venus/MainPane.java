package rars.venus;

import rars.venus.registers.ControlAndStatusWindow;
import rars.venus.registers.FloatingPointWindow;
import rars.venus.registers.RegistersWindow;

import javax.swing.*;
import java.awt.*;

/*
Copyright (c) 2003-2006,  Pete Sanderson and Kenneth Vollmar

Developed by Pete Sanderson (psanderson@otterbein.edu)
and Kenneth Vollmar (kenvollmar@missouristate.edu)

Permission is hereby granted, free of charge, to any person obtaining 
a copy of this software and associated documentation files (the 
"Software"), to deal in the Software without restriction, including 
without limitation the rights to use, copy, modify, merge, publish, 
distribute, sublicense, and/or sell copies of the Software, and to 
permit persons to whom the Software is furnished to do so, subject to 
the following conditions:

The above copyright notice and this permission notice shall be 
included in all copies or substantial portions of the Software.

THE SOFTWARE IS PROVIDED "AS IS", WITHOUT WARRANTY OF ANY KIND, 
EXPRESS OR IMPLIED, INCLUDING BUT NOT LIMITED TO THE WARRANTIES OF 
MERCHANTABILITY, FITNESS FOR A PARTICULAR PURPOSE AND NONINFRINGEMENT.
IN NO EVENT SHALL THE AUTHORS OR COPYRIGHT HOLDERS BE LIABLE FOR ANY 
CLAIM, DAMAGES OR OTHER LIABILITY, WHETHER IN AN ACTION OF CONTRACT, 
TORT OR OTHERWISE, ARISING FROM, OUT OF OR IN CONNECTION WITH THE 
SOFTWARE OR THE USE OR OTHER DEALINGS IN THE SOFTWARE.

(MIT license, http://www.opensource.org/licenses/mit-license.html)
 */

/**
 * Container for the per-file editor tabs.  Each top-level tab is a
 * {@link FileViewPane} representing one open file with "Edit" and "Memory"
 * sub-tabs.  The memory/registers view (ExecutePane) is a singleton shared by
 * all files: it is moved into the sub-tab of the file whose "Memory" tab is
 * selected (see {@link #attachMemoryView(FileViewPane)}).
 *
 * @author Sanderson and Bumgarner
 **/

public class MainPane extends JPanel {
    EditTabbedPane editTabbedPane;
    ExecutePane executeTab;

    private VenusUI mainUI;
    // The file tab currently hosting the shared ExecutePane, or null.
    private FileViewPane memoryHost;
    // The internal frame bounds are initialized on the first hosting only;
    // afterwards they travel with the pane.
    private boolean executeBoundsInitialized = false;

    /**
     * Constructor for the MainPane class.
     **/

    public MainPane(VenusUI appFrame, Editor editor, RegistersWindow regs,
                    FloatingPointWindow cop1Regs, ControlAndStatusWindow cop0Regs) {
        super(new BorderLayout());
        this.mainUI = appFrame;

        editTabbedPane = new EditTabbedPane(appFrame, editor, this);
        executeTab = new ExecutePane(appFrame, regs, cop1Regs, cop0Regs);

        this.add(editTabbedPane, BorderLayout.CENTER);
    }

    /**
     * Returns current edit pane.
     *
     * @return the editor pane
     */
    public EditPane getEditPane() {
        return editTabbedPane.getCurrentEditTab();
    }

    /**
     * Returns the top-level tabbed pane of open files.
     *
     * @return the editor tabbed pane
     */
    public EditTabbedPane getEditTabbedPane() {
        return editTabbedPane;
    }

    /**
     * returns the shared memory/registers view.
     *
     * @return the execute pane
     */
    public ExecutePane getExecutePane() {
        return executeTab;
    }

    /**
     * returns the shared memory/registers view.
     * Same as getExecutePane().
     *
     * @return the execute pane
     */
    public ExecutePane getExecuteTab() {
        return executeTab;
    }

    /**
     * The file tab currently hosting the shared memory view, or null.
     */
    public FileViewPane getMemoryHost() {
        return memoryHost;
    }

    /**
     * Move the shared memory/registers view into the given file's "Memory"
     * sub-tab (detaching it from the file that hosted it before, if any).
     * Called when a file's "Memory" sub-tab is selected.  On the first
     * hosting ever, the internal window bounds are initialized (from the
     * persisted layout if available).
     *
     * @param host the file tab to host the view, or null to just detach.
     */
    public void attachMemoryView(FileViewPane host) {
        if (memoryHost == host) {
            return;
        }
        if (memoryHost != null) {
            memoryHost.detachMemoryView();
        }
        memoryHost = host;
        if (host != null) {
            host.hostMemoryView(executeTab);
            if (!executeBoundsInitialized) {
                executeBoundsInitialized = true;
                if (UiLayoutPersistence.hasSavedLayout()) {
                    executeTab.applyPersistedWindowBounds();
                } else {
                    executeTab.setWindowBounds();
                }
            }
        }
    }

    /**
     * Detach the shared memory view if it is currently hosted by the given
     * file tab (called before that file tab is removed).
     */
    public void detachMemoryView(FileViewPane host) {
        if (memoryHost == host) {
            memoryHost = null;
            host.detachMemoryView();
        }
    }

}
