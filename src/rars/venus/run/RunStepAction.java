package rars.venus.run;

import rars.Globals;
import rars.Settings;
import rars.SimulationException;
import rars.riscv.hardware.RegisterFile;
import rars.simulator.ProgramArgumentList;
import rars.simulator.Simulator;
import rars.simulator.SimulatorNotice;
import rars.venus.EditPane;
import rars.venus.ExecutePane;
import rars.venus.FileStatus;
import rars.venus.GuiAction;
import rars.venus.VenusUI;

import javax.swing.*;
import java.awt.*;
import java.awt.event.ActionEvent;
import java.util.Observable;
import java.util.Observer;

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
 * Action  for the Run -> Step menu item
 */
public class RunStepAction extends GuiAction {

    private String name;
    private ExecutePane executePane;
    private VenusUI mainUI;
    // The tab of the program being stepped by this action.  Its per-file
    // status is updated when the step completes.
    private EditPane steppingPane;

    public RunStepAction(String name, Icon icon, String descrip,
                         Integer mnemonic, KeyStroke accel, VenusUI gui) {
        super(name, icon, descrip, mnemonic, accel);
        mainUI = gui;
    }

    /**
     * perform next simulated instruction step.
     */
    public void actionPerformed(ActionEvent e) {
        name = this.getValue(Action.NAME).toString();
        executePane = mainUI.getMainPane().getExecutePane();
        EditPane currentPane = mainUI.getMainPane().getEditPane();
        // Defensive: make sure the shared simulator state holds the current
        // file's program (it normally always does once its tab is selected).
        if (currentPane != null && currentPane.getAssembledProgram() != null
                && Globals.program != currentPane.getAssembledProgram()) {
            mainUI.getMainPane().getEditTabbedPane().activateProject(currentPane);
        }
        steppingPane = currentPane;
        // If the current file has not been assembled yet (never assembled, or
        // edited since the last assembly), assemble it automatically first.
        // F5/F7 are available while editing and start with an assembly; if it
        // fails, the errors are already reported and stepping is skipped.
        if (!FileStatus.isAssembled()
                || FileStatus.get() == FileStatus.EDITED
                || FileStatus.get() == FileStatus.NOT_EDITED) {
            mainUI.getRunAssembleAction().actionPerformed(e);
            if (!FileStatus.isAssembled()) {
                return; // assembly failed
            }
        }
        if (FileStatus.get() == FileStatus.TERMINATED) {
            // The program previously ran to completion; reset the state so
            // the next step starts from the beginning.
            if (!RunResetAction.performReset(mainUI)) {
                return; // reset failed, do not step
            }
        }
        mainUI.getMessagesPane().selectRunMessageTab();
        executePane.getTextSegmentWindow().setCodeHighlighting(true);

        if (!mainUI.getStarted()) {  // DPS 17-July-2008
            processProgramArgumentsIfAny();
            if (!FunctionRunSupport.prepareFunctionRunIfNeeded(mainUI)) {
                return; // user cancelled the function-arguments dialog
            }
            // Program not started yet: the first F7 press enters the
            // "Prepare" state.  Arguments are set up above and the first
            // line to be executed is highlighted, but no instruction is
            // executed yet.  The next F7 press performs the first step.
            mainUI.setStarted(true);
            // From the first F7 press on, the program is under execution
            // control: block editing of its text and tab switching.
            mainUI.getMainPane().getEditTabbedPane().beginRunSession(currentPane);
            mainUI.highlightStepSourceLine(RegisterFile.getProgramCounter());
            return;
        }

        mainUI.setStarted(true);
        mainUI.getMainPane().getEditTabbedPane().beginRunSession(currentPane); // no-op if already active

        // Setup callback for after step finishes
        final Observer stopListener =
                new Observer() {
                    public void update(Observable o, Object simulator) {
                        SimulatorNotice notice = ((SimulatorNotice) simulator);
                        if (notice.getAction() != SimulatorNotice.SIMULATOR_STOP) return;
                        EventQueue.invokeLater(() -> stepped(notice.getDone(), notice.getReason(), notice.getException()));
                        o.deleteObserver(this);
                    }
                };
        Simulator.getInstance().addObserver(stopListener);

        Globals.program.startSimulation(1, null);
    }

    // When step is completed, control returns here (from execution thread, indirectly)
    // to update the GUI.
    public void stepped(boolean done, Simulator.Reason reason, SimulationException pe) {
        executePane.getRegistersWindow().updateRegisters();
        executePane.getFloatingPointWindow().updateRegisters();
        executePane.getControlAndStatusWindow().updateRegisters();
        executePane.getDataSegmentWindow().updateValues();
        if (!done) {
            mainUI.highlightStep(RegisterFile.getProgramCounter());
            mainUI.highlightStepSourceLine(RegisterFile.getProgramCounter());
            FileStatus.set(FileStatus.RUNNABLE);
            if (steppingPane != null) {
                steppingPane.setFileStatus(FileStatus.RUNNABLE);
            }
        }
        if (done) {
            RunGoAction.resetMaxSteps();
            executePane.getTextSegmentWindow().unhighlightAllSteps();
            mainUI.clearHighlightedLines();
            FileStatus.set(FileStatus.TERMINATED);
            if (steppingPane != null) {
                steppingPane.setFileStatus(FileStatus.TERMINATED);
            }
            // If this finished a run that was paused mid-run, unlock editing
            // and tab switching.
            mainUI.getMainPane().getEditTabbedPane().endRunSession();
        }
        if (done && pe == null) {
            if (reason == Simulator.Reason.NORMAL_TERMINATION) {
                FunctionRunSupport.reportFunctionResult(mainUI);
            }
            mainUI.getMessagesPane().postMessage(
                    "\n" + name + ": execution " +
                            ((reason == Simulator.Reason.CLIFF_TERMINATION) ? "terminated due to null instruction."
                                    : "completed successfully.") + "\n\n");
            mainUI.getMessagesPane().postRunMessage(
                    "\n-- program is finished running" +
                            ((reason == Simulator.Reason.CLIFF_TERMINATION) ? "(dropped off bottom)" : " (" + Globals.exitCode + ")") + " --\n\n");
            mainUI.getMessagesPane().selectRunMessageTab();
        }
        if (pe != null) {
            RunGoAction.resetMaxSteps();
            mainUI.getMessagesPane().postMessage(
                    pe.error().generateReport());
            mainUI.getMessagesPane().postMessage(
                    "\n" + name + ": execution terminated with errors.\n\n");
            mainUI.getRegistersPane().setSelectedComponent(executePane.getControlAndStatusWindow());
            FileStatus.set(FileStatus.TERMINATED); // should be redundant.
            if (steppingPane != null) {
                steppingPane.setFileStatus(FileStatus.TERMINATED);
            }
            mainUI.getMainPane().getEditTabbedPane().endRunSession();
            executePane.getTextSegmentWindow().setCodeHighlighting(true);
            executePane.getTextSegmentWindow().unhighlightAllSteps();
            mainUI.highlightStep(RegisterFile.getProgramCounter() - 4);
            mainUI.clearHighlightedLines();
        }
        mainUI.setReset(false);
    }

    ////////////////////////////////////////////////////////////////////////////////////
    // Method to store any program arguments into MIPS memory and registers before
    // execution begins. Arguments go into the gap between $sp and kernel memory.
    // Argument pointers and count go into runtime stack and $sp is adjusted accordingly.
    // $a0 gets argument count (argc), $a1 gets stack address of first arg pointer (argv).
    private void processProgramArgumentsIfAny() {
        String programArguments = executePane.getTextSegmentWindow().getProgramArguments();
        if (programArguments == null || programArguments.length() == 0 ||
                !Globals.getSettings().getBooleanSetting(Settings.Bool.PROGRAM_ARGUMENTS)) {
            return;
        }
        new ProgramArgumentList(programArguments).storeProgramArguments();
    }
}