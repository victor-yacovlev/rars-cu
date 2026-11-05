package rars.venus.editors;

import javax.swing.text.Document;
import javax.swing.undo.UndoManager;
import java.awt.*;

/*
Copyright (c) 2003-2010,  Pete Sanderson and Kenneth Vollmar

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
 * Specifies capabilities that any test editor used in MARS must have.
 */

public interface TextEditingArea {

    // Used by Find/Replace
    public static final int TEXT_NOT_FOUND = 0;
    public static final int TEXT_FOUND = 1;
    public static final int TEXT_REPLACED_FOUND_NEXT = 2;
    public static final int TEXT_REPLACED_NOT_FOUND_NEXT = 3;


    public void copy();

    public void cut();

    public int doFindText(String find, boolean caseSensitive);

    public int doReplace(String find, String replace, boolean caseSensitive);

    public int doReplaceAll(String find, String replace, boolean caseSensitive);

    public int getCaretPosition();

    public Document getDocument();

    public String getSelectedText();

    public int getSelectionEnd();

    public int getSelectionStart();

    public void select(int selectionStart, int selectionEnd);

    public void selectAll();

    public String getText();

    public UndoManager getUndoManager();

    public void paste();

    public void replaceSelection(String str);

    public void setCaretPosition(int position);

    public void setEditable(boolean editable);

    /**
     * Whether the text can currently be edited (see setEditable).
     */
    public boolean isEditable();

    /**
     * Updates the component's colors to reflect the current editability
     * (see setEditable): a read-only text gets a slightly darker background.
     */
    public void updateEditableAppearance();

    /**
     * Puts the editing area into (or out of) the "locked" state used while
     * the file's program is executing: the text is read-only, the caret is
     * hidden and cannot be moved, text cannot be selected and the
     * current-line highlight is switched off.  Scrolling (wheel, scrollbars)
     * still works.  Equivalent to setEditable plus the interaction locks.
     */
    public void setLocked(boolean locked);

    /**
     * The background tint used for a read-only text (program under execution
     * control): a light gray that differs only slightly from the normal
     * background (white becomes #F2F2F2).
     */
    public static Color readOnlyBackground(Color base) {
        return new Color(
                (int) (base.getRed() * 0.95),
                (int) (base.getGreen() * 0.95),
                (int) (base.getBlue() * 0.95));
    }

    /**
     * Returns the current background color of the editing area.
     */
    public Color getBackground();

    public void setSelectionEnd(int pos);

    public void setSelectionStart(int pos);

    public void setText(String text);

    public void setFont(Font f);

    public Font getFont();

    public boolean requestFocusInWindow();

    public FontMetrics getFontMetrics(Font f);

    public void setBackground(Color c);

    public void setEnabled(boolean enabled);

    public void grabFocus();

    public void redo();

    public void revalidate();

    public void setSourceCode(String code, boolean editable);

    public void setCaretVisible(boolean vis);

    public void setSelectionVisible(boolean vis);

    public void undo();

    public void discardAllUndoableEdits();

    public void setLineHighlightEnabled(boolean highlight);

    /**
     * Highlights (as a background drawn under the text, not a selection) the
     * given source line.  Lines are numbered starting with 1.  A value of 0
     * or less clears any previously set highlight.
     *
     * @param line The line to highlight, or 0/negative to clear.
     */
    public void setHighlightedLine(int line);

    public void setCaretBlinkRate(int rate);

    public void setTabSize(int chars);

    public void updateSyntaxStyles();

    public Component getOuterComponent();
}