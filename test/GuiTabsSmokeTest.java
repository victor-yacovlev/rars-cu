import rars.Globals;
import rars.Settings;
import rars.venus.EditPane;
import rars.venus.EditTabbedPane;
import rars.venus.FileViewPane;
import rars.venus.FileStatus;
import rars.venus.VenusUI;

import javax.swing.*;
import java.awt.Color;
import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.function.BooleanSupplier;

/**
 * GUI smoke test for per-file projects: every open file is an independent
 * project, assembling/running one file must not be invalidated by switching
 * to another tab and back, and only one program may run at a time.
 *
 * Run with:  gradle runGuiSmokeTest
 */
public class GuiTabsSmokeTest {
    private static int failures = 0;

    public static void main(String[] args) throws Exception {
        int exitCode = 0;
        try {
            exitCode = run(args);
        } catch (Throwable t) {
            t.printStackTrace();
            exitCode = 1;
        }
        System.exit(exitCode);
    }

    private static int run(String[] args) throws Exception {
        File fileA = tempFile("gui_smoke_loop.s",
                ".text\nmain:\n    j main\n");
        File fileB = tempFile("gui_smoke_exit.s",
                ".text\nmain:\n    li a7, 93\n    li a0, 42\n    ecall\n");
        try {
            return runScenario(fileA, fileB);
        } finally {
            cleanup(fileA, fileB);
        }
    }

    private static int runScenario(File fileAraw, File fileBraw) throws Exception {
        // EditTabbedPane stores canonical paths (see FileOpener.openFile).
        final File fileA = fileAraw.getCanonicalFile();
        final File fileB = fileBraw.getCanonicalFile();

        ArrayList<String> paths = new ArrayList<>();
        paths.add(fileA.getPath());
        paths.add(fileB.getPath());
        VenusUI ui = new VenusUI("RARS GUI smoke test", paths);
        EditTabbedPane tabs = ui.getMainPane().getEditTabbedPane();
        sleep(500);

        EditPane paneA = tabs.getEditPaneForFile(fileA.getPath());
        EditPane paneB = tabs.getEditPaneForFile(fileB.getPath());
        check("both files opened in tabs", paneA != null && paneB != null);
        check("last opened file is selected", tabs.getCurrentEditTab() == paneB);

        // Four settings are force-enabled and removed from the Settings menu.
        check("forced setting: program arguments enabled",
                Globals.getSettings().getBooleanSetting(Settings.Bool.PROGRAM_ARGUMENTS));
        check("forced setting: extended (pseudo) instructions enabled",
                Globals.getSettings().getBooleanSetting(Settings.Bool.EXTENDED_ASSEMBLER_ENABLED));
        check("forced setting: rv64 enabled",
                Globals.getSettings().getBooleanSetting(Settings.Bool.RV64_ENABLED));
        check("forced setting: assemble-on-open enabled",
                Globals.getSettings().getBooleanSetting(Settings.Bool.ASSEMBLE_ON_OPEN));
        check("Settings menu: forced items removed",
                !settingsMenuHasItem(ui, "Program arguments provided to program")
                        && !settingsMenuHasItem(ui, "Permit extended (pseudo) instructions and formats")
                        && !settingsMenuHasItem(ui, "64 bit")
                        && !settingsMenuHasItem(ui, "Assemble file upon opening"));

        // Assemble file B (current tab) and verify it is runnable.
        onEdt(() -> ui.getRunAssembleAction().actionPerformed(null));
        check("after assemble: menu state RUNNABLE", VenusUI.getMenuState() == FileStatus.RUNNABLE);
        check("after assemble: stays on the Edit sub-tab (assembly is for diagnostics)",
                tabs.getCurrentFileView().getSelectedIndex() == FileViewPane.EDIT_TAB);
        // Line numbers margin: always visible, opaque, light-gray background
        // (the same tone used for the locked editor background), styled with a
        // vertical separator on the right.
        javax.swing.JComponent numbers = tabs.getCurrentFileView().getEditPane().getLineNumbersComponent();
        check("line numbers margin is visible",
                numbers.isVisible());
        check("after assemble: FileStatus.assembled", FileStatus.isAssembled());
        check("after assemble: per-file assembled", paneB.isAssembled());

        // Switch to the other tab (file A, never assembled).
        onEdt(() -> tabs.setCurrentEditTab(paneA));
        check("switched to unassembled file: menu state NOT_EDITED",
                VenusUI.getMenuState() == FileStatus.NOT_EDITED);
        check("switched to unassembled file: not assembled", !FileStatus.isAssembled());

        // Switch back to file B: its project must be restored and runnable
        // again (this is the reported bug: run actions used to stay disabled).
        onEdt(() -> tabs.setCurrentEditTab(paneB));
        check("switched back: menu state RUNNABLE", VenusUI.getMenuState() == FileStatus.RUNNABLE);
        check("switched back: FileStatus.assembled", FileStatus.isAssembled());
        check("switched back: current file is B", FileStatus.getFile() != null
                && FileStatus.getFile().getPath().equals(fileB.getPath()));

        // Run file B to completion after the round trip through the other tab.
        onEdt(() -> ui.getRunGoAction().actionPerformed(null));
        waitFor("file B runs to TERMINATED after tab round trip",
                () -> VenusUI.getMenuState() == FileStatus.TERMINATED, 10000);
        check("file B exit code is 42", Globals.exitCode == 42);

        // Now assemble file A (an endless loop) and start it.
        onEdt(() -> tabs.setCurrentEditTab(paneA));
        onEdt(() -> ui.getRunAssembleAction().actionPerformed(null));
        check("file A assembled: menu state RUNNABLE", VenusUI.getMenuState() == FileStatus.RUNNABLE);
        onEdt(() -> ui.getRunGoAction().actionPerformed(null));
        waitFor("file A runs (menu state RUNNING)",
                () -> VenusUI.getMenuState() == FileStatus.RUNNING, 10000);

        // While A is executing, editing its text is blocked.
        check("while running: source of running program is read-only",
                !paneA.isSourceEditable());
        check("while running: other program's source stays editable",
                paneB.isSourceEditable());

        // While A is executing, switching to B must be refused (only one
        // program at a time, shared simulator state).
        onEdt(() -> tabs.setCurrentEditTab(paneB));
        check("switching tabs while running is refused",
                tabs.getCurrentEditTab() == paneA);

        // Pause: execution is suspended but the run session stays active,
        // so editing and tab switching remain blocked.
        onEdt(() -> rars.simulator.Simulator.getInstance().pauseExecution());
        waitFor("file A paused (menu state RUNNABLE)",
                () -> VenusUI.getMenuState() == FileStatus.RUNNABLE, 10000);
        check("while paused: source stays read-only", !paneA.isSourceEditable());
        onEdt(() -> tabs.setCurrentEditTab(paneB));
        check("while paused: switching tabs is refused",
                tabs.getCurrentEditTab() == paneA);
        check("while paused: Stop is available",
                ui.getRunStopAction().isEnabled());

        // Resume the paused program (Go continues from the pause point), then
        // stop it.
        onEdt(() -> ui.getRunGoAction().actionPerformed(null));
        waitFor("file A resumed (menu state RUNNING)",
                () -> VenusUI.getMenuState() == FileStatus.RUNNING, 10000);
        onEdt(() -> rars.simulator.Simulator.getInstance().stopExecution());
        waitFor("file A stopped (menu state TERMINATED)",
                () -> VenusUI.getMenuState() == FileStatus.TERMINATED, 10000);
        check("after stop: source is editable again", paneA.isSourceEditable());
        onEdt(() -> tabs.setCurrentEditTab(paneB));
        check("after stop: switch to B allowed and its project is RUNNABLE again",
                tabs.getCurrentEditTab() == paneB && VenusUI.getMenuState() == FileStatus.RUNNABLE);

        // Step mode (reported bug): from the FIRST F7 press (Prepare state,
        // before any instruction executes) the program text must be read-only
        // and other tabs unreachable.
        final java.awt.Color bgBefore = paneB.getSourceBackground();
        onEdt(() -> ui.getRunStepAction().actionPerformed(null));
        check("after first F7 (prepare): source is read-only",
                !paneB.isSourceEditable());
        check("after first F7 (prepare): background tinted",
                !paneB.getSourceBackground().equals(bgBefore));
        check("after first F7 (prepare): edit actions disabled",
                !ui.areEditActionsEnabled());
        check("after first F7 (prepare): other tab shown disabled",
                !tabs.isEnabledAt(tabs.indexOfComponent(tabs.fileViewFor(paneA)))
                        && tabs.isEnabledAt(tabs.indexOfComponent(tabs.fileViewFor(paneB))));
        check("after first F7 (prepare): editor not focusable (no keyboard cursor movement)",
                !paneB.getSourceComponent().isFocusable());
        onEdt(() -> tabs.setCurrentEditTab(paneA));
        check("after first F7 (prepare): switching tabs is refused",
                tabs.getCurrentEditTab() == paneB);

        // Step through to completion: the program has 3 instructions.
        for (int i = 0; i < 10 && VenusUI.getMenuState() != FileStatus.TERMINATED; i++) {
            onEdt(() -> ui.getRunStepAction().actionPerformed(null));
        }
        check("stepped to completion: menu state TERMINATED",
                VenusUI.getMenuState() == FileStatus.TERMINATED);
        check("stepped to completion: source is editable again",
                paneB.isSourceEditable());
        check("stepped to completion: background restored",
                paneB.getSourceBackground().equals(bgBefore));
        check("stepped to completion: edit actions enabled",
                ui.areEditActionsEnabled());
        check("stepped to completion: tab enabled again",
                tabs.isEnabledAt(tabs.indexOfComponent(tabs.fileViewFor(paneA))));
        check("stepped to completion: editor focusable again",
                paneB.getSourceComponent().isFocusable());
        onEdt(() -> tabs.setCurrentEditTab(paneA));
        check("stepped to completion: switching tabs is allowed again",
                tabs.getCurrentEditTab() == paneA);

        // Stop (F11): must always be available during execution, including
        // step mode, and force-terminate the program.
        onEdt(() -> tabs.setCurrentEditTab(paneB));
        onEdt(() -> ui.getRunStepAction().actionPerformed(null)); // Prepare state
        check("during stepping (prepare): Stop is available",
                ui.getRunStopAction().isEnabled());
        onEdt(() -> ui.getRunStepAction().actionPerformed(null)); // 1 instruction
        check("during stepping (after a step): Stop is available",
                ui.getRunStopAction().isEnabled());
        onEdt(() -> ui.getRunStopAction().actionPerformed(null));
        check("Stop during stepping: execution terminated",
                VenusUI.getMenuState() == FileStatus.TERMINATED);
        check("Stop during stepping: Stop unavailable again",
                !ui.getRunStopAction().isEnabled());
        check("Stop during stepping: source editable again",
                paneB.isSourceEditable());
        onEdt(() -> tabs.setCurrentEditTab(paneA));
        check("Stop during stepping: switching tabs allowed again",
                tabs.getCurrentEditTab() == paneA);

        // Edited text: F5/F7 stay available and assemble automatically first.
        onEdt(() -> tabs.setCurrentEditTab(paneB));
        onEdt(() -> paneB.setSourceCode(paneB.getSource() + "\n# edited\n", true));
        check("after edit: menu state EDITED", VenusUI.getMenuState() == FileStatus.EDITED);
        check("after edit: Go is available", ui.getRunGoAction().isEnabled());
        check("after edit: Step is available", ui.getRunStepAction().isEnabled());
        Globals.exitCode = -1;
        onEdt(() -> ui.getRunGoAction().actionPerformed(null));
        waitFor("edited program auto-assembled and ran to completion",
                () -> VenusUI.getMenuState() == FileStatus.TERMINATED, 10000);
        check("auto-assembly before Go: program ran (exit code 42)",
                Globals.exitCode == 42);
        { // margin background matches the locked-editor tone in both states
            javax.swing.JComponent margin = tabs.getCurrentFileView().getEditPane().getLineNumbersComponent();
            java.awt.Color marginBg = margin.getBackground();
            java.awt.Color base = rars.Globals.getSettings().getColorSettingByPosition(rars.Settings.EDITOR_BACKGROUND);
            java.awt.Color lockedTone = rars.venus.editors.TextEditingArea.readOnlyBackground(base);
            check("line numbers margin uses the locked-editor background tone",
                    marginBg.equals(lockedTone));
        }

        // Broken text: the automatic assembly fails, no run happens, and
        // F5/F7 remain available.
        onEdt(() -> paneB.setSourceCode(".text\nmain:\n    badinstruction x1, x2, x3\n", true));
        check("after broken edit: menu state EDITED", VenusUI.getMenuState() == FileStatus.EDITED);
        onEdt(() -> ui.getRunGoAction().actionPerformed(null));
        check("failed auto-assembly: program not assembled", !FileStatus.isAssembled());
        check("failed auto-assembly: program did not run",
                VenusUI.getMenuState() == FileStatus.NOT_EDITED);
        check("failed auto-assembly: Go still available", ui.getRunGoAction().isEnabled());
        check("failed auto-assembly: Step still available", ui.getRunStepAction().isEnabled());

        // The "Execute" tab is now "Memory": switching to it force-assembles
        // the current file.
        check("file tab has a Memory sub-tab",
                "Memory".equals(tabs.getCurrentFileView().getTitleAt(FileViewPane.MEMORY_TAB)));
        onEdt(() -> tabs.getCurrentFileView().showMemory());
        check("switch to Memory with broken program: bounced back to Edit",
                tabs.getCurrentFileView().getSelectedIndex() == FileViewPane.EDIT_TAB);
        check("switch to Memory with broken program: still not assembled",
                !FileStatus.isAssembled());
        // Restore a valid program and switch again: assembly succeeds now.
        onEdt(() -> paneB.setSourceCode(
                ".text\nmain:\n    li a7, 93\n    li a0, 42\n    ecall\n", true));
        onEdt(() -> tabs.getCurrentFileView().showMemory());
        check("switch to Memory assembles: menu state RUNNABLE",
                VenusUI.getMenuState() == FileStatus.RUNNABLE);
        check("switch to Memory assembles: stays on the Memory sub-tab",
                tabs.getCurrentFileView().getSelectedIndex() == FileViewPane.MEMORY_TAB);
        { // after the forced assembly the windows are re-laid out
            javax.swing.JInternalFrame ts = ui.getMainPane().getExecutePane().getTextSegmentWindow();
            java.awt.Rectangle desk = new java.awt.Rectangle(ui.getMainPane().getExecutePane().getSize());
            java.awt.Rectangle b = ts.getBounds();
            check("after forced assembly: Text Segment re-laid out (visible, large enough)",
                    !ts.isIcon() && desk.contains(b) && b.width >= desk.width / 2 && b.height >= desk.height / 3);
        }
        onEdt(() -> tabs.getCurrentFileView().showEdit());

        // Breakpoints in the editor line numbers margin, synced with the
        // Text Segment "Bkpt" column.
        rars.venus.TextSegmentWindow textSegment = ui.getMainPane().getExecutePane().getTextSegmentWindow();
        javax.swing.JComponent bpNumbers = tabs.getCurrentFileView().getEditPane().getLineNumbersComponent();
        int bpLineHeight = bpNumbers.getFontMetrics(bpNumbers.getFont()).getHeight();
        // click on line 3 (executable "li a7, 93"): sets a breakpoint
        onEdt(() -> dispatchMarginClick(bpNumbers, 2 * bpLineHeight + bpLineHeight / 2));
        check("margin click sets breakpoint marker on line 3",
                tabs.getCurrentFileView().getEditPane().getBreakpointLines().contains(3));
        check("margin click sets Bkpt in Text Segment",
                textSegment.getSortedBreakPointsArray() != null
                        && textSegment.getSortedBreakPointsArray().length >= 1);
        // click again: clears the breakpoint
        onEdt(() -> dispatchMarginClick(bpNumbers, 2 * bpLineHeight + bpLineHeight / 2));
        check("second margin click clears the breakpoint marker",
                !tabs.getCurrentFileView().getEditPane().getBreakpointLines().contains(3));
        check("second margin click clears Bkpt in Text Segment",
                textSegment.getSortedBreakPointsArray() == null
                        || textSegment.getSortedBreakPointsArray().length == 0);
        // sync from the table side: set breakpoints via the Text Segment
        java.util.ArrayList<Integer> addr3 = new java.util.ArrayList<>();
        for (rars.ProgramStatement st : rars.Globals.program.getMachineList()) {
            if (fileB.getPath().equals(st.getSourceFile()) && st.getSourceLine() == 3) {
                addr3.add(st.getAddress());
            }
        }
        int[] addr3arr = addr3.stream().mapToInt(Integer::intValue).toArray();
        check("file B line 3 is executable", addr3arr.length >= 1);
        onEdt(() -> textSegment.setBreakpointsAtAddresses(addr3arr, true));
        check("Bkpt checkbox -> editor marker appears",
                tabs.getCurrentFileView().getEditPane().getBreakpointLines().contains(3));
        // click on a non-executable line (line 1 is the ".text" directive): no breakpoint
        onEdt(() -> dispatchMarginClick(bpNumbers, bpLineHeight / 2));
        check("margin click on non-executable line does nothing",
                !tabs.getCurrentFileView().getEditPane().getBreakpointLines().contains(1)
                        && !tabs.getCurrentFileView().getEditPane().getBreakpointLines().contains(2)
                        && (textSegment.getSortedBreakPointsArray() == null
                            || textSegment.getSortedBreakPointsArray().length == addr3arr.length));
        // clear all: markers disappear
        onEdt(() -> textSegment.clearAllBreakpoints());
        check("clear-all breakpoints clears editor markers",
                tabs.getCurrentFileView().getEditPane().getBreakpointLines().isEmpty());

        if (failures == 0) {
            System.out.println("\nGUI smoke test: ALL CHECKS PASSED");
            return 0;
        }
        System.out.println("\nGUI smoke test: " + failures + " CHECK(S) FAILED");
        return 1;
    }

    // Whether the Settings menu contains a menu item with the given label.
    private static boolean settingsMenuHasItem(VenusUI ui, String label) {
        javax.swing.JMenuBar mb = ui.menu;
        for (int i = 0; i < mb.getMenuCount(); i++) {
            javax.swing.JMenu m = mb.getMenu(i);
            if (m == null || !"Settings".equals(m.getText())) {
                continue;
            }
            for (int j = 0; j < m.getItemCount(); j++) {
                javax.swing.JMenuItem item = m.getItem(j);
                if (item != null && label.equals(item.getText())) {
                    return true;
                }
            }
        }
        return false;
    }

    // Simulates a mouse click on the line numbers margin at the given y.
    private static void dispatchMarginClick(javax.swing.JComponent numbers, int y) {
        numbers.dispatchEvent(new java.awt.event.MouseEvent(numbers,
                java.awt.event.MouseEvent.MOUSE_CLICKED, System.currentTimeMillis(),
                0, 5, y, 1, false));
    }

    private static File tempFile(String name, String content) throws IOException {
        File f = File.createTempFile(name.replace(".s", ""), ".s");
        Files.write(f.toPath(), content.getBytes());
        return f;
    }

    private static void onEdt(Runnable r) throws Exception {
        SwingUtilities.invokeAndWait(r);
        sleep(100);
    }

    private static void waitFor(String what, BooleanSupplier condition, long timeoutMs)
            throws InterruptedException {
        long deadline = System.currentTimeMillis() + timeoutMs;
        while (System.currentTimeMillis() < deadline) {
            if (condition.getAsBoolean()) {
                return;
            }
            sleep(50);
        }
        check(what + " [timeout]", false);
    }

    private static void check(String what, boolean ok) {
        System.out.println((ok ? "PASS: " : "FAIL: ") + what);
        if (!ok) {
            failures++;
        }
    }

    private static void sleep(long ms) {
        try {
            Thread.sleep(ms);
        } catch (InterruptedException ignored) {
        }
    }

    private static void cleanup(File... files) {
        for (File f : files) {
            //noinspection ResultOfMethodCallIgnored
            f.delete();
        }
    }
}
