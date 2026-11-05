package rars.venus.run;

import rars.Settings;
import rars.Globals;
import rars.util.SystemIO;
import rars.venus.EditPane;
import rars.venus.ExecutePane;
import rars.venus.FileStatus;
import rars.venus.GuiAction;
import rars.venus.VenusUI;
import rars.simulator.Simulator;

import javax.swing.*;
import java.awt.event.ActionEvent;

/*
Copyright (c) 2003-2007,  Pete Sanderson and Kenneth Vollmar

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
 * Action class for the Run -> Stop menu item (and toolbar icon).
 *
 * Stop is always available while a program is executing and force-terminates
 * it.  Two cases are distinguished:
 * <ul>
 *   <li>a simulation thread is running (Go) &mdash; the thread is interrupted
 *       and RunGoAction's "stopped" method performs the cleanup;</li>
 *   <li>no simulation thread is running, but a run session is active (step
 *       mode between instructions, or a paused program) &mdash; there is
 *       nothing to interrupt, so the session is terminated directly via
 *       {@link #forceTerminate}.</li>
 * </ul>
 */
public class RunStopAction extends GuiAction {
    private VenusUI mainUI;

    public RunStopAction(String name, Icon icon, String descrip,
                         Integer mnemonic, KeyStroke accel, VenusUI gui) {
        super(name, icon, descrip, mnemonic, accel);
        mainUI = gui;
    }

    public void actionPerformed(ActionEvent e) {
        String name = this.getValue(Action.NAME).toString();
        if (Simulator.getInstance().isSimulating()) {
            Simulator.getInstance().stopExecution();
            // RunGoAction's "stopped" method will take care of the cleanup.
            return;
        }
        forceTerminate(mainUI, name);
    }

    /**
     * Force-terminates the current execution session without a running
     * simulation thread (step mode or paused program).  Mirrors the cleanup
     * of RunGoAction.stopped() for a user stop: final register and data
     * segment values are shown, status becomes TERMINATED, the run session
     * (editing/tab-switch locks) ends.  The next Go/Step starts from the
     * beginning (an automatic Reset is performed first).
     *
     * @param mainUI the main GUI
     * @param name   name of the invoking action, for the message pane
     */
    public static void forceTerminate(VenusUI mainUI, String name) {
        ExecutePane executePane = mainUI.getMainPane().getExecutePane();
        EditPane currentPane = mainUI.getMainPane().getEditPane();
        // show final register and data segment values.
        executePane.getRegistersWindow().updateRegisters();
        executePane.getFloatingPointWindow().updateRegisters();
        executePane.getControlAndStatusWindow().updateRegisters();
        executePane.getDataSegmentWindow().updateValues();
        FileStatus.set(FileStatus.TERMINATED);
        if (currentPane != null) {
            currentPane.setFileStatus(FileStatus.TERMINATED);
        }
        // Execution has finished; no "next line" to highlight in the editor.
        mainUI.clearHighlightedLines();
        SystemIO.resetFiles(); // close any files opened in the program
        mainUI.getMessagesPane().postMessage(
                "\n" + name + ": execution terminated by user.\n\n");
        mainUI.getMessagesPane().selectMessageTab();
        RunGoAction.resetMaxSteps();
        mainUI.setReset(false);
        // Execution is finished: unlock editing and tab switching.
        mainUI.getMainPane().getEditTabbedPane().endRunSession();
    }
}
