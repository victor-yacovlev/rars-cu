package rars.venus;

import rars.Globals;
import rars.RISCVprogram;
import rars.Settings;
import rars.venus.editors.TextEditingArea;
import rars.venus.editors.generic.GenericTextArea;
import rars.venus.editors.jeditsyntax.JEditBasedTextArea;

import javax.swing.*;
import javax.swing.event.DocumentEvent;
import javax.swing.event.DocumentListener;
import javax.swing.undo.CompoundEdit;
import javax.swing.undo.UndoManager;
import java.awt.*;
import java.awt.event.ItemEvent;
import java.awt.event.ItemListener;
import java.io.BufferedReader;
import java.io.IOException;
import java.io.StringReader;
import java.util.ArrayList;
import java.util.Observable;
import java.util.Observer;

/*
Copyright (c) 2003-2011,  Pete Sanderson and Kenneth Vollmar

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
 * Represents one file opened for editing.  Maintains required internal structures.
 * Before Mars 4.0, there was only one editor pane, a tab, and only one file could
 * be open at a time.  With 4.0 came the multifile (pane, tab) editor, and existing
 * duties were split between EditPane and the new EditTabbedPane class.
 *
 * @author Sanderson and Bumgarner
 */

public class EditPane extends JPanel implements Observer {

    private TextEditingArea sourceCode;
    private VenusUI mainUI;
    private String currentDirectoryPath;
    private JLabel caretPositionLabel;
    private JLabel lineNumbers;
    // Source lines (1-based) of this file that currently have breakpoints set
    // (kept in sync with the Bkpt column of the Text Segment window).
    private java.util.Set<Integer> breakpointLines = new java.util.HashSet<>();
    private static int count = 0;
    private boolean isCompoundEdit = false;
    private CompoundEdit compoundEdit;
    private FileStatus fileStatus;
    // Per-file project state: the program assembled from this file, together
    // with the inputs needed to re-assemble it (used to restore the project
    // when the tab is re-activated, and by Reset).  Each open file is treated
    // as an independent project.
    private RISCVprogram assembledProgram;
    private ArrayList<RISCVprogram> assembledProgramList;
    private boolean extendedAssemblerEnabled;
    private boolean warningsAreErrors;

    /**
     * Constructor for the EditPane class.
     */

    public EditPane(VenusUI appFrame) {
        super(new BorderLayout());
        this.mainUI = appFrame;
        // user.dir, user's current working directory, is guaranteed to have a value
        currentDirectoryPath = System.getProperty("user.dir");
        // mainUI.editor = new Editor(mainUI);
        // We want to be notified of editor font changes! See update() below.
        Globals.getSettings().addObserver(this);
        this.fileStatus = new FileStatus();
        // The line numbers margin paints its background, light-red rectangles
        // under lines with breakpoints, and the numbers (HTML text) on top.
        lineNumbers = new JLabel() {
            @Override
            protected void paintComponent(Graphics g) {
                // margin background
                g.setColor(getBackground());
                g.fillRect(0, 0, getWidth(), getHeight());
                // light-red rectangle under every line with a breakpoint:
                // full width of the margin, one text line high
                g.setColor(BREAKPOINT_MARKER_BACKGROUND);
                int lineHeight = getFontMetrics(getFont()).getHeight();
                if (lineHeight > 0) {
                    Rectangle visible = getVisibleRect();
                    for (int line : breakpointLines) {
                        int y = (line - 1) * lineHeight;
                        if (y + lineHeight < visible.y || y > visible.y + visible.height) {
                            continue;
                        }
                        g.fillRect(0, y, getWidth(), lineHeight);
                    }
                }
                super.paintComponent(g); // the numbers text (label is not opaque)
            }
        };

        if (Globals.getSettings().getBooleanSetting(Settings.Bool.GENERIC_TEXT_EDITOR)) {
            this.sourceCode = new GenericTextArea(this, lineNumbers);
        } else {
            this.sourceCode = new JEditBasedTextArea(this, lineNumbers);
        }
        // sourceCode is responsible for its own scrolling
        this.add(this.sourceCode.getOuterComponent(), BorderLayout.CENTER);

        // If source code is modified, will set flag to trigger/request file save.
        sourceCode.getDocument().addDocumentListener(
                new DocumentListener() {
                    public void insertUpdate(DocumentEvent evt) {
                        // IF statement added DPS 9-Aug-2011
                        // This method is triggered when file contents added to document
                        // upon opening, even though not edited by user.  The IF
                        // statement will sense this situation and immediately return.
                        if (FileStatus.get() == FileStatus.OPENING) {
                            setFileStatus(FileStatus.NOT_EDITED);
                            FileStatus.set(FileStatus.NOT_EDITED);
                            lineNumbers.setText(getLineNumbersList(sourceCode.getDocument()));
                            return;
                        }
                        // End of 9-Aug-2011 modification.
                        if (getFileStatus() == FileStatus.NEW_NOT_EDITED) {
                            setFileStatus(FileStatus.NEW_EDITED);
                        }
                        if (getFileStatus() == FileStatus.NOT_EDITED) {
                            setFileStatus(FileStatus.EDITED);
                        }
                        // Editing a file invalidates its assembled program: the
                        // file has to be assembled again before it can be run.
                        if (getFileStatus() == FileStatus.RUNNABLE || getFileStatus() == FileStatus.TERMINATED) {
                            setFileStatus(FileStatus.EDITED);
                            clearAssemblyState();
                            // keep the static (legacy) flag in sync with this pane
                            FileStatus.setAssembled(false);
                            // Normally impossible while a run session is active (the
                            // text is read-only), but if a change slipped through,
                            // abandon the run session cleanly instead of leaving
                            // the program mid-run without execution controls.
                            EditTabbedPane editTabbedPane = mainUI.getMainPane().getEditTabbedPane();
                            if (editTabbedPane.getRunSessionPane() == EditPane.this) {
                                editTabbedPane.endRunSession();
                            }
                        }
                        if (getFileStatus() == FileStatus.NEW_EDITED) {
                            mainUI.getEditor().setTitle("", getFilename(), getFileStatus());
                        } else {
                            mainUI.getEditor().setTitle(getPathname(), getFilename(), getFileStatus());
                        }

                        FileStatus.setEdited(true);
                        switch (FileStatus.get()) {
                            case FileStatus.NEW_NOT_EDITED:
                                FileStatus.set(FileStatus.NEW_EDITED);
                                break;
                            case FileStatus.NEW_EDITED:
                                break;
                            default:
                                FileStatus.set(FileStatus.EDITED);
                        }

                        Globals.getGui().getMainPane().getExecutePane().clearPane(); // DPS 9-Aug-2011

                        lineNumbers.setText(getLineNumbersList(sourceCode.getDocument()));
                    }

                    public void removeUpdate(DocumentEvent evt) {
                        this.insertUpdate(evt);
                    }

                    public void changedUpdate(DocumentEvent evt) {
                        this.insertUpdate(evt);
                    }
                });

        this.setSourceCode("", false);

        lineNumbers.setFont(getLineNumberFont(sourceCode.getFont()));
        lineNumbers.setVerticalAlignment(JLabel.TOP);
        // Populate from the (empty) document right away: setting empty text on
        // the area fires no insert event, so the DocumentListener below would
        // never run for a freshly created file and the margin would collapse.
        lineNumbers.setText(getLineNumbersList(sourceCode.getDocument()));
        lineNumbers.setVisible(true);
        applyLineNumbersStyle();
        // Clicking a line number toggles a breakpoint for that line (if the
        // line is executable), in sync with the Text Segment Bkpt column.
        lineNumbers.addMouseListener(new java.awt.event.MouseAdapter() {
            @Override
            public void mouseClicked(java.awt.event.MouseEvent e) {
                toggleBreakpointAtLineNumber(lineNumberAt(e.getY()));
            }
        });

        JPanel editInfo = new JPanel(new BorderLayout());
        caretPositionLabel = new JLabel();
        caretPositionLabel.setToolTipText("Tracks the current position of the text editing cursor.");
        displayCaretPosition(new Point());
        editInfo.add(caretPositionLabel, BorderLayout.WEST);
        this.add(editInfo, BorderLayout.SOUTH);
    }

    /**
     * Styles the line numbers margin: opaque light-gray background (the same
     * tone used for the read-only editor state) and a vertical separator line
     * on the right edge of the numbers block.  Re-applied on settings changes.
     */
    // Light-red background of the breakpoint marker rectangle in the margin.
    private static final Color BREAKPOINT_MARKER_BACKGROUND = new Color(0xF8, 0xC0, 0xC0);

    private void applyLineNumbersStyle() {
        Color background = Globals.getSettings().getColorSettingByPosition(Settings.EDITOR_BACKGROUND);
        Color foreground = Globals.getSettings().getColorSettingByPosition(Settings.EDITOR_FOREGROUND);
        Color separator = UIManager.getColor("Separator.foreground");
        if (separator == null) {
            separator = foreground.brighter();
        }
        // Not opaque: the margin paints its background and the breakpoint
        // rectangles itself (see the lineNumbers paintComponent override).
        lineNumbers.setOpaque(false);
        lineNumbers.setBackground(rars.venus.editors.TextEditingArea.readOnlyBackground(background));
        lineNumbers.setForeground(foreground);
        lineNumbers.setBorder(javax.swing.BorderFactory.createCompoundBorder(
                javax.swing.BorderFactory.createEmptyBorder(0, 6, 0, 6),
                javax.swing.BorderFactory.createMatteBorder(0, 0, 0, 1, separator)));
        lineNumbers.revalidate();
        lineNumbers.repaint();
    }


    /**
     * For initalizing the source code when opening an ASM file
     *
     * @param s        String containing text
     * @param editable set true if code is editable else false
     */

    public void setSourceCode(String s, boolean editable) {
        sourceCode.setSourceCode(s, editable);
    }

    /**
     * Get rid of any accumulated undoable edits.  It is useful to call
     * this method after opening a file into the text area.  The
     * act of setting its text content upon reading the file will generate
     * an undoable edit.  Normally you don't want a freshly-opened file
     * to appear with its Undo action enabled.  But it will unless you
     * call this after setting the text.
     */
    public void discardAllUndoableEdits() {
        sourceCode.discardAllUndoableEdits();
    }

    /**
     * Form string with source code line numbers.
     * Resulting string is HTML, for which JLabel will happily honor <br> to do
     * multiline label (it ignores '\n').  The line number list is a JLabel with
     * one line number per line.
     */
    private static final String spaces = "&nbsp;&nbsp;&nbsp;&nbsp;&nbsp;&nbsp;&nbsp;&nbsp;&nbsp;";

    public String getLineNumbersList(javax.swing.text.Document doc) {
        StringBuffer lineNumberList = new StringBuffer("<html>");
        int lineCount = doc.getDefaultRootElement().getElementCount(); //this.getSourceLineCount();
        int digits = Integer.toString(lineCount).length();
        for (int i = 1; i <= lineCount; i++) {
            String lineStr = Integer.toString(i);
            int leadingSpaces = digits - lineStr.length();
            String padded = (leadingSpaces == 0)
                    ? lineStr + "&nbsp;"
                    : spaces.substring(0, leadingSpaces * 6) + lineStr + "&nbsp;";
            lineNumberList.append(padded).append("<br>");
        }
        lineNumberList.append("<br></html>");
        return lineNumberList.toString();
    }


    /**
     * Calculate and return number of lines in source code text.
     * Do this by counting newline characters then adding one if last line does
     * not end with newline character.
     */

   	/*  IMPLEMENTATION NOTE:
        * Tried repeatedly to use StringTokenizer to count lines but got bad results
   	 * on empty lines (consecutive delimiters) even when returning delimiter as token.
   	 * BufferedReader on StringReader seems to work better.
   	 */
    public int getSourceLineCount() {
        BufferedReader bufStringReader = new BufferedReader(new StringReader(sourceCode.getText()));
        int lineNums = 0;
        try {
            while (bufStringReader.readLine() != null) {
                lineNums++;
            }
        } catch (IOException e) {
        }
        return lineNums;
    }

    /**
     * Get source code text
     *
     * @return Sting containing source code
     */
    public String getSource() {
        return sourceCode.getText();
    }


    /**
     * Set the editing status for this EditPane's associated document.
     * For the argument, use one of the constants from class FileStatus.
     *
     * @param fileStatus the status constant from class FileStatus
     */
    public void setFileStatus(int fileStatus) {
        this.fileStatus.setFileStatus(fileStatus);
    }


    /**
     * Get the editing status for this EditPane's associated document.
     * This will be one of the constants from class FileStatus.
     */

    public int getFileStatus() {
        return this.fileStatus.getFileStatus();
    }

    /**
     * Whether this file's project has been successfully assembled.
     *
     * @return true if an assembled program is stored for this file.
     */
    public boolean isAssembled() {
        return this.fileStatus.isFileAssembled();
    }

    /**
     * Store the assembly result for this file's project.  Called after a
     * successful assembly so the project can be restored later (e.g. when
     * switching back to this tab) without losing the ability to run it.
     *
     * @param program                  the assembled program ( Globals.program )
     * @param programList              the list of assembled source units
     * @param extendedAssemblerEnabled extended-assembler setting used for assembly
     * @param warningsAreErrors        warnings-as-errors setting used for assembly
     */
    public void setAssemblyState(RISCVprogram program, ArrayList<RISCVprogram> programList,
                                 boolean extendedAssemblerEnabled, boolean warningsAreErrors) {
        this.assembledProgram = program;
        this.assembledProgramList = programList;
        this.extendedAssemblerEnabled = extendedAssemblerEnabled;
        this.warningsAreErrors = warningsAreErrors;
        this.fileStatus.setFileAssembled(program != null);
    }

    /**
     * Discard the assembled program of this file's project (after editing the
     * file, or when restoring the project fails).
     */
    public void clearAssemblyState() {
        setAssemblyState(null, null, false, false);
    }

    /**
     * Whether the source text can currently be edited.  It cannot be edited
     * while the file's program is executing.
     */
    public boolean isSourceEditable() {
        return this.sourceCode.isEditable();
    }

    /**
     * Enable or disable editing of the source text.  Used to prevent edits
     * while the file's program is executing.  A locked text is read-only and
     * visually marked: slightly darker background, no caret, no selection,
     * no current-line highlight.
     *
     * @param editable true to allow editing, false to lock the text.
     */
    public void setSourceEditable(boolean editable) {
        if (editable == isSourceEditable()) {
            return; // no change; do not disturb the current state
        }
        this.sourceCode.setLocked(!editable);
    }

    /**
     * The AWT component of the source editing area (exposed for GUI tests).
     */
    public Component getSourceComponent() {
        return (Component) this.sourceCode;
    }

    /**
     * The line numbers margin component (exposed for GUI tests).
     */
    public JComponent getLineNumbersComponent() {
        return lineNumbers;
    }

    /**
     * The source lines currently marked with breakpoints in the margin
     * (exposed for GUI tests; kept in sync with the Text Segment Bkpt column).
     */
    public java.util.Set<Integer> getBreakpointLines() {
        return breakpointLines;
    }

    /**
     * Re-derive the set of breakpoint lines of this file from the Bkpt column
     * of the Text Segment window and refresh the margin markers.
     */
    public void updateBreakpointMarkers() {
        VenusUI gui = Globals.getGui();
        if (gui == null) {
            return;
        }
        rars.venus.TextSegmentWindow textSegment =
                gui.getMainPane().getExecutePane().getTextSegmentWindow();
        breakpointLines = textSegment.getBreakpointSourceLines(getPathname());
        lineNumbers.setText(getLineNumbersList(sourceCode.getDocument()));
        lineNumbers.repaint();
    }

    /**
     * Map a y-coordinate in the line numbers margin to a source line number
     * (1-based), clamped to the number of lines in the document.
     */
    private int lineNumberAt(int y) {
        int lineHeight = lineNumbers.getFontMetrics(lineNumbers.getFont()).getHeight();
        if (lineHeight <= 0) {
            return -1;
        }
        int line = y / lineHeight + 1;
        int lineCount = getSourceLineCount();
        return Math.max(1, Math.min(line, lineCount));
    }

    /**
     * Toggle a breakpoint for the given source line: all machine instructions
     * generated by the line are marked (or unmarked) in the Text Segment Bkpt
     * column.  Only possible in the editor of the currently loaded project
     * and only for executable lines (lines that generate instructions).
     */
    public void toggleBreakpointAtLineNumber(int line) {
        if (line <= 0) {
            return;
        }
        VenusUI gui = Globals.getGui();
        if (gui == null) {
            return;
        }
        // Only the program of the currently loaded project can have breakpoints.
        if (gui.getMainPane().getEditTabbedPane().getLoadedProjectPane() != this) {
            return;
        }
        // Not while a program is executing (the editor is locked anyway).
        if (rars.simulator.Simulator.getInstance().isSimulating()
                || gui.getMainPane().getEditTabbedPane().isRunSessionActive()) {
            return;
        }
        if (Globals.program == null) {
            return;
        }
        rars.venus.TextSegmentWindow textSegment =
                gui.getMainPane().getExecutePane().getTextSegmentWindow();
        java.util.ArrayList<Integer> addresses = new java.util.ArrayList<>();
        boolean allSet = true;
        String path = getPathname();
        for (rars.ProgramStatement statement : Globals.program.getMachineList()) {
            if (path != null && path.equals(statement.getSourceFile())
                    && statement.getSourceLine() == line) {
                addresses.add(statement.getAddress());
                if (!textSegment.isBreakpointSetAtAddress(statement.getAddress())) {
                    allSet = false;
                }
            }
        }
        if (addresses.isEmpty()) {
            return; // the line generates no instructions: no breakpoint possible
        }
        int[] addressArray = new int[addresses.size()];
        for (int i = 0; i < addressArray.length; i++) {
            addressArray[i] = addresses.get(i);
        }
        textSegment.setBreakpointsAtAddresses(addressArray, !allSet);
        // markers refresh via the breakpoints-changed callback; do it directly
        // as well so the margin is consistent even without a callback wired.
        updateBreakpointMarkers();
    }

    /**
     * The current background color of the source editing area.
     */
    public Color getSourceBackground() {
        return this.sourceCode.getBackground();
    }

    /**
     * The program assembled from this file, or null if it has none.
     */
    public RISCVprogram getAssembledProgram() {
        return assembledProgram;
    }

    /**
     * The list of assembled source units of this file's project, or null.
     */
    public ArrayList<RISCVprogram> getAssembledProgramList() {
        return assembledProgramList;
    }

    /**
     * Extended-assembler option that was in effect when this project was assembled.
     */
    public boolean getExtendedAssemblerEnabled() {
        return extendedAssemblerEnabled;
    }

    /**
     * Warnings-as-errors option that was in effect when this project was assembled.
     */
    public boolean getWarningsAreErrors() {
        return warningsAreErrors;
    }

    /**
     * Delegates to corresponding FileStatus method
     */
    public String getFilename() {
        return this.fileStatus.getFilename();
    }


    /**
     * Delegates to corresponding FileStatus method
     */
    public String getPathname() {
        return this.fileStatus.getPathname();
    }


    /**
     * Delegates to corresponding FileStatus method
     */
    public void setPathname(String pathname) {
        this.fileStatus.setPathname(pathname);
    }

    /**
     * Delegates to corresponding FileStatus method
     */
    public boolean hasUnsavedEdits() {
        return this.fileStatus.hasUnsavedEdits();
    }


    /**
     * Delegates to corresponding FileStatus method
     */
    public boolean isNew() {
        return this.fileStatus.isNew();
    }


    /**
     * Delegates to text area's requestFocusInWindow method.
     */

    public void tellEditingComponentToRequestFocusInWindow() {
        this.sourceCode.requestFocusInWindow();
    }


    /**
     * Delegates to corresponding FileStatus method
     */
    public void updateStaticFileStatus() {
        fileStatus.updateStaticFileStatus();
    }


    /**
     * get the manager in charge of Undo and Redo operations
     *
     * @return the UnDo manager
     */
    public UndoManager getUndoManager() {
        return sourceCode.getUndoManager();
    }

      /*       Note: these are invoked only when copy/cut/paste are used from the
                      toolbar or menu or the defined menu Alt codes.  When
   						Ctrl-C, Ctrl-X or Ctrl-V are used, this code is NOT invoked
   						but the operation works correctly!
   				The "set visible" operations are used because clicking on the toolbar
   				icon causes both the selection highlighting AND the blinking cursor
   				to disappear!  This does not happen when using menu selection or 
   				Ctrl-C/X/V
   	*/

    /**
     * copy currently-selected text into clipboard
     */
    public void copyText() {
        sourceCode.copy();
        sourceCode.setCaretVisible(true);
        sourceCode.setSelectionVisible(true);
    }

    /**
     * cut currently-selected text into clipboard
     */
    public void cutText() {
        sourceCode.cut();
        sourceCode.setCaretVisible(true);
    }

    /**
     * paste clipboard contents at cursor position
     */
    public void pasteText() {
        sourceCode.paste();
        sourceCode.setCaretVisible(true);
    }

    /**
     * select all text
     */
    public void selectAllText() {
        sourceCode.selectAll();
        sourceCode.setCaretVisible(true);
        sourceCode.setSelectionVisible(true);
    }

    /**
     * Undo previous edit
     */
    public void undo() {
        sourceCode.undo();
    }

    /**
     * Redo previous edit
     */
    public void redo() {
        sourceCode.redo();
    }

    /**
     * Update state of Edit menu's Undo and Redo menu items.
     */
    public void updateUndoAndRedoState() {
        mainUI.updateUndoAndRedoState();
    }

    /**
     * get editor's line number display status
     *
    /**
     * enable or disable checkbox that controls display of line numbers
     *
    /**
     * Update the caret position label on the editor's border to
     * display the current line and column.  The position is given
     * as text stream offset and will be converted into line and column.
     *
     * @param pos Offset into the text stream of caret.
     */
    public void displayCaretPosition(int pos) {
        displayCaretPosition(convertStreamPositionToLineColumn(pos));
    }

    /**
     * Display cursor coordinates
     *
     * @param p Point object with x-y (column, line number) coordinates of cursor
     */
    public void displayCaretPosition(Point p) {
        caretPositionLabel.setText("Line: " + p.y + " Column: " + p.x);
    }

    private static final char newline = '\n';

    /**
     * Given byte stream position in text being edited, calculate its column and line
     * number coordinates.
     *
     * @param position position of character
     * @return position Its column and line number coordinate as a Point.
     */
    public Point convertStreamPositionToLineColumn(int position) {
        String textStream = sourceCode.getText();
        int line = 1;
        int column = 1;
        for (int i = 0; i < position; i++) {
            if (textStream.charAt(i) == newline) {
                line++;
                column = 1;
            } else {
                column++;
            }
        }
        return new Point(column, line);
    }

    /**
     * Given line and column (position in the line) numbers, calculate
     * its byte stream position in text being edited.
     *
     * @param line   Line number in file (starts with 1)
     * @param column Position within that line (starts with 1)
     * @return corresponding stream position.  Returns -1 if there is no corresponding position.
     */
    public int convertLineColumnToStreamPosition(int line, int column) {
        String textStream = sourceCode.getText();
        int textLength = textStream.length();
        int textLine = 1;
        int textColumn = 1;
        for (int i = 0; i < textLength; i++) {
            if (textLine == line && textColumn == column) {
                return i;
            }
            if (textStream.charAt(i) == newline) {
                textLine++;
                textColumn = 1;
            } else {
                textColumn++;
            }
        }
        return -1;
    }

    /**
     * Highlights the specified editor text line as a full-width background
     * drawn under the text, as a distinct UI element from the text selection.
     * Lines are numbered starting with 1, consistent with line numbers
     * displayed by the editor.  A value of 0 or less clears the highlight.
     *
     * @param line The desired line number of this TextPane's text.  Numbering
     *             starts at 1.
     */
    public void setHighlightedLine(int line) {
        sourceCode.setHighlightedLine(line);
    }

    /**
     * Select the specified editor text line.  Lines are numbered starting with 1, consistent
     * with line numbers displayed by the editor.
     *
     * @param line The desired line number of this TextPane's text.  Numbering starts at 1, and
     *             nothing will happen if the parameter value is less than 1
     */
    public void selectLine(int line) {
        if (line > 0) {
            int lineStartPosition = convertLineColumnToStreamPosition(line, 1);
            int lineEndPosition = convertLineColumnToStreamPosition(line + 1, 1) - 1;
            if (lineEndPosition < 0) { // DPS 19 Sept 2012.  Happens if "line" is last line of file.

                lineEndPosition = sourceCode.getText().length() - 1;
            }
            if (lineStartPosition >= 0) {
                sourceCode.select(lineStartPosition, lineEndPosition);
                sourceCode.setSelectionVisible(true);
            }
        }
    }


    /**
     * Select the specified editor text line.  Lines are numbered starting with 1, consistent
     * with line numbers displayed by the editor.
     *
     * @param line   The desired line number of this TextPane's text.  Numbering starts at 1, and
     *               nothing will happen if the parameter value is less than 1
     * @param column Desired column at which to place the cursor.
     */
    public void selectLine(int line, int column) {
        selectLine(line);
        // Made one attempt at setting cursor; didn't work but here's the attempt
        // (imagine using it in the one-parameter overloaded method above)
        //sourceCode.setCaretPosition(lineStartPosition+column-1);
    }

    /**
     * Finds next occurrence of text in a forward search of a string. Search begins
     * at the current cursor location, and wraps around when the end of the string
     * is reached.
     *
     * @param find          the text to locate in the string
     * @param caseSensitive true if search is to be case-sensitive, false otherwise
     * @return TEXT_FOUND or TEXT_NOT_FOUND, depending on the result.
     */
    public int doFindText(String find, boolean caseSensitive) {
        return sourceCode.doFindText(find, caseSensitive);
    }

    /**
     * Finds and replaces next occurrence of text in a string in a forward search.
     * If cursor is initially at end
     * of matching selection, will immediately replace then find and select the
     * next occurrence if any.  Otherwise it performs a find operation.  The replace
     * can be undone with one undo operation.
     *
     * @param find          the text to locate in the string
     * @param replace       the text to replace the find text with - if the find text exists
     * @param caseSensitive true for case sensitive. false to ignore case
     * @return Returns TEXT_FOUND if not initially at end of selected match and matching
     * occurrence is found.  Returns TEXT_NOT_FOUND if the text is not matched.
     * Returns TEXT_REPLACED_NOT_FOUND_NEXT if replacement is successful but there are
     * no additional matches.  Returns TEXT_REPLACED_FOUND_NEXT if reaplacement is
     * successful and there is at least one additional match.
     */
    public int doReplace(String find, String replace, boolean caseSensitive) {
        return sourceCode.doReplace(find, replace, caseSensitive);
    }


    /**
     * Finds and replaces <B>ALL</B> occurrences of text in a string in a forward search.
     * All replacements are bundled into one CompoundEdit, so one Undo operation will
     * undo all of them.
     *
     * @param find          the text to locate in the string
     * @param replace       the text to replace the find text with - if the find text exists
     * @param caseSensitive true for case sensitive. false to ignore case
     * @return the number of occurrences that were matched and replaced.
     */
    public int doReplaceAll(String find, String replace, boolean caseSensitive) {
        return sourceCode.doReplaceAll(find, replace, caseSensitive);
    }


    /**
     * Update, if source code is visible, when Font setting changes.
     * This method is specified by the Observer interface.
     */
    public void update(Observable fontChanger, Object arg) {
        sourceCode.setFont(Globals.getSettings().getEditorFont());
        sourceCode.setLineHighlightEnabled(Globals.getSettings().getBooleanSetting(Settings.Bool.EDITOR_CURRENT_LINE_HIGHLIGHTING));
        sourceCode.setCaretBlinkRate(Globals.getSettings().getCaretBlinkRate());
        sourceCode.setTabSize(Globals.getSettings().getEditorTabSize());
        sourceCode.updateSyntaxStyles();
        sourceCode.revalidate();
        // We want line numbers to be displayed same size but always PLAIN style.
        // Easiest way to get same pixel height as source code is to set to same
        // font family as the source code! It can get a bit complicated otherwise
        // because different fonts will render the same font size in different
        // pixel heights.  This is a factor because the line numbers as displayed
        // in the editor form a separate column from the source code and if the
        // pixel height is not the same then the numbers will not line up with
        // the source lines.
        lineNumbers.setFont(getLineNumberFont(sourceCode.getFont()));
        applyLineNumbersStyle();
        lineNumbers.revalidate();
    }


    /* Private helper method.
     * Determine font to use for editor line number display, given current
     * font for source code.
     */
    private Font getLineNumberFont(Font sourceFont) {
        return (sourceCode.getFont().getStyle() == Font.PLAIN)
                ? sourceFont
                : new Font(sourceFont.getFamily(), Font.PLAIN, sourceFont.getSize());
    }


}