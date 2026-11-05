package rars.venus;

import rars.Globals;
import rars.simulator.Simulator;
import rars.venus.registers.ControlAndStatusWindow;
import rars.venus.registers.FloatingPointWindow;
import rars.venus.registers.RegistersWindow;

import javax.swing.*;
import javax.swing.event.ChangeEvent;
import javax.swing.event.ChangeListener;
import javax.swing.plaf.basic.BasicTabbedPaneUI;
import java.awt.*;
import java.awt.event.ActionEvent;

	
	/*
Copyright (c) 2003-2006,  Pete Sanderson and Kenneth Vollmar

Developed by Pete Sanderson (psanderson@otterbein.edu)
and Kenneth Vollmar (kenvollmar@missouristate.edu)

Permission is hereby granted, free of charge, to any person obtaining 
a copy of this software and associated documentation files (the 
"Software"), to deal in the Software without restriction, including 
without limitation the rights to use, copy, modify, merge, publish, 
distribute, sublicense, and/or sell copies of the Software, and to 
permit persons to whom the Software is furnished to do so, subject 
to the following conditions:

The above copyright notice and this permission notice shall be 
included in all copies or substantial portions of the Software.

THE SOFTWARE IS PROVIDED "AS IS", WITHOUT WARRANTY OF ANY KIND, 
EXPRESS OR IMPLIED, INCLUDING BUT NOT LIMITED TO THE WARRANTIES OF 
MERCHANTABILITY, FITNESS FOR A PARTICULAR PURPOSE AND NONINFRINGEMENT. 
IN NO EVENT SHALL THE AUTHORS OR COPYRIGHT HOLDERS BE LIABLE FOR 
ANY CLAIM, DAMAGES OR OTHER LIABILITY, WHETHER IN AN ACTION OF 
CONTRACT, TORT OR OTHERWISE, ARISING FROM, OUT OF OR IN CONNECTION 
WITH THE SOFTWARE OR THE USE OR OTHER DEALINGS IN THE SOFTWARE.

(MIT license, http://www.opensource.org/licenses/mit-license.html)
 */

/**
 * Creates the tabbed areas in the UI and also created the internal windows that
 * exist in them.
 *
 * @author Sanderson and Bumgarner
 **/

public class MainPane extends JTabbedPane {
    EditPane editTab;
    ExecutePane executeTab;
    EditTabbedPane editTabbedPane;

    private VenusUI mainUI;
    // Guards the assemble-on-Memory-tab-selection listener against
    // re-entrancy (tab selection changes made while assembling).
    private boolean assemblingForMemoryView = false;

    /**
     * Constructor for the MainPane class.
     **/

    public MainPane(VenusUI appFrame, Editor editor, RegistersWindow regs,
                    FloatingPointWindow cop1Regs, ControlAndStatusWindow cop0Regs) {
        super();
        this.mainUI = appFrame;

        this.setTabPlacement(JTabbedPane.TOP); //LEFT);
        if (this.getUI() instanceof BasicTabbedPaneUI) {
            BasicTabbedPaneUI ui = (BasicTabbedPaneUI) this.getUI();
        }
        editTabbedPane = new EditTabbedPane(appFrame, editor, this);
        executeTab = new ExecutePane(appFrame, regs, cop1Regs, cop0Regs);
        String editTabTitle = "Edit";
        String executeTabTitle = "Memory";
        Icon editTabIcon = null;//new ImageIcon(Toolkit.getDefaultToolkit().getImage(this.getClass().getResource(Globals.imagesPath+"Edit_tab.jpg")));
        Icon executeTabIcon = null;//new ImageIcon(Toolkit.getDefaultToolkit().getImage(this.getClass().getResource(Globals.imagesPath+"Execute_tab.jpg")));

        this.setTabLayoutPolicy(JTabbedPane.SCROLL_TAB_LAYOUT);
        this.addTab(editTabTitle, editTabIcon, editTabbedPane);
        this.setForeground(Color.black);

        this.addTab(executeTabTitle, executeTabIcon, executeTab);

        this.setToolTipTextAt(0, "Text editor for composing RISCV programs.");
        this.setToolTipTextAt(1, "View memory and registers of the assembled program.  Switching to this tab re-assembles the current file.");
      
      	/* Listener has one specific purpose: when Execute tab is selected for the 
           * first time, set the bounds of its internal frames by invoking the
      	 * setWindowsBounds() method.  Once this occurs, listener removes itself!
      	 * We do NOT want to reset bounds each time Execute tab is selected.
      	 * See ExecutePane.setWindowsBounds documentation for more details.
      	 */
        this.addChangeListener(
                new ChangeListener() {
                    public void stateChanged(ChangeEvent ce) {
                        JTabbedPane tabbedPane = (JTabbedPane) ce.getSource();
                        int index = tabbedPane.getSelectedIndex();
                        Component c = tabbedPane.getComponentAt(index);
                        ExecutePane executePane = Globals.getGui().getMainPane().getExecutePane();
                        if (c == executePane) {
                            if (UiLayoutPersistence.hasSavedLayout()) {
                                executePane.applyPersistedWindowBounds();
                            } else {
                                executePane.setWindowBounds();
                            }
                            Globals.getGui().getMainPane().removeChangeListener(this);
                        }
                    }
                });

        // The Memory tab shows memory and registers of the assembled program,
        // so switching to it force-assembles the current file: the view then
        // always corresponds to the current source text.  While a program is
        // executing (run session active), the live execution state is shown
        // instead and no assembly is performed.  If the assembly fails, the
        // user is returned to the Edit tab (errors are in the Messages pane).
        this.addChangeListener(
                new ChangeListener() {
                    public void stateChanged(ChangeEvent ce) {
                        JTabbedPane tabbedPane = (JTabbedPane) ce.getSource();
                        int index = tabbedPane.getSelectedIndex();
                        Component c = (index >= 0) ? tabbedPane.getComponentAt(index) : null;
                        if (c != executeTab || assemblingForMemoryView) {
                            return;
                        }
                        if (Simulator.getInstance().isSimulating()
                                || editTabbedPane.isRunSessionActive()) {
                            return; // show the live execution state
                        }
                        assemblingForMemoryView = true;
                        try {
                            Globals.getGui().getRunAssembleAction().actionPerformed(
                                    new ActionEvent(this, ActionEvent.ACTION_PERFORMED, "MemoryTab"));
                            if (FileStatus.get() != FileStatus.RUNNABLE) {
                                // nothing to show (no file open or assembly errors)
                                setSelectedComponent(editTabbedPane);
                            } else {
                                // Re-layout the internal windows so the Text
                                // Segment window of the freshly assembled
                                // program is visible and large enough.  Deferred
                                // until the tab activation layout pass is done,
                                // so the final desktop size is used.
                                SwingUtilities.invokeLater(() -> executeTab.relayoutWindows());
                            }
                        } finally {
                            assemblingForMemoryView = false;
                        }
                    }
                });
    }

    /**
     * Returns current edit pane.  Implementation changed for MARS 4.0 support
     * for multiple panes, but specification is same.
     *
     * @return the editor pane
     */
    public EditPane getEditPane() {
        return editTabbedPane.getCurrentEditTab();
    }

    /**
     * Returns component containing editor display
     *
     * @return the editor tabbed pane
     */
    public EditTabbedPane getEditTabbedPane() {
        return editTabbedPane;
    }

    /**
     * returns component containing execution-time display
     *
     * @return the execute pane
     */
    public ExecutePane getExecutePane() {
        return executeTab;
    }

    /**
     * returns component containing execution-time display.
     * Same as getExecutePane().
     *
     * @return the execute pane
     */
    public ExecutePane getExecuteTab() {
        return executeTab;
    }

}
