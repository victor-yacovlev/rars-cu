package rars.venus.run;

import rars.Globals;
import rars.RISCVprogram;
import rars.assembler.Symbol;
import rars.assembler.SymbolTable;
import rars.riscv.hardware.AddressErrorException;
import rars.riscv.hardware.Memory;
import rars.riscv.hardware.Register;
import rars.riscv.hardware.RegisterFile;
import rars.simulator.FunctionRun;
import rars.venus.VenusUI;

import javax.swing.*;
import java.awt.*;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Support for running a program as a function call when it has no global
 * 'main' or '_start' label. In that case the entry point is the first text
 * symbol of the global symbol table (in order of appearance in the source),
 * the user is asked for integer argument values (through a modal dialog) and
 * they are placed into a0..a7 (further arguments go onto the stack) following
 * the RISC-V calling convention. The return address register receives a
 * sentinel address so that 'ret' terminates execution normally.
 *
 * If the source line above the function label is a comment ('#' or '//')
 * containing a C function signature, the dialog instead asks for a fixed
 * set of named parameters and, if the signature has a non-void return type,
 * the result is reported to the Messages pane after execution.
 */
public final class FunctionRunSupport {

    private static final int MAX_REGISTER_ARGS = 8; // a0..a7
    private static final int DEFAULT_STACK_SLOTS = 64; // safety limit for stack arguments

    private FunctionRunSupport() {
    }

    private static class FunctionSignature {
        final String name;
        final String returnType;
        final List<String> paramNames;

        FunctionSignature(String name, String returnType, List<String> paramNames) {
            this.name = name;
            this.returnType = returnType;
            this.paramNames = paramNames;
        }

        boolean hasReturnValue() {
            return returnType != null && !returnType.isEmpty() && !returnType.equals("void");
        }
    }

    /**
     * To be called once before a run session starts (when the program is
     * assembled but execution has not started yet). Detects the function-run
     * case, asks the user for arguments and configures PC/ra/argument
     * registers.
     *
     * @return false if the user cancelled the dialog; the run must not start.
     *         true if the run may proceed (including when function-run mode
     *         does not apply, e.g. when 'main' or '_start' is defined).
     */
    public static boolean prepareFunctionRunIfNeeded(VenusUI mainUI) {
        if (FunctionRun.active) {
            return true; // already configured for this run session
        }
        SymbolTable globals = Globals.symbolTable;
        if (isTextSymbolDefined(globals, "main") || isTextSymbolDefined(globals, "_start")) {
            return true;
        }
        Symbol entry = firstGlobalTextSymbol(globals);
        if (entry == null) {
            return true; // nothing that looks like a function; run as usual
        }

        String functionName = entry.getName();
        FunctionSignature signature = findSignature(functionName);
        long[] args = promptForArguments(mainUI, functionName, signature);
        if (args == null) {
            return false; // user cancelled
        }
        applyFunctionCall(entry, signature, args);
        mainUI.getMessagesPane().postMessage(
                "Running function '" + functionName + "' with arguments ("
                        + joinArguments(args) + ")\n\n");
        return true;
    }

    /**
     * Reports the function result to the Messages pane after a normal
     * termination of a function-run session.
     */
    public static void reportFunctionResult(VenusUI mainUI) {
        if (!FunctionRun.active || FunctionRun.functionName == null) {
            return;
        }
        StringBuilder message = new StringBuilder("Execution Done: ");
        message.append(FunctionRun.functionName).append("(");
        message.append(joinArguments(FunctionRun.arguments));
        message.append(")");
        if (FunctionRun.hasReturnValue) {
            long result = RegisterFile.getRegister("a0").getValue();
            message.append(" = ").append(result);
        }
        mainUI.getMessagesPane().postRunMessage(message.toString() + "\n\n");
    }

    private static boolean isTextSymbolDefined(SymbolTable table, String name) {
        int address = table.getAddress(name);
        return address != SymbolTable.NOT_FOUND && Memory.inTextSegment(address);
    }

    private static Symbol firstGlobalTextSymbol(SymbolTable table) {
        for (Symbol symbol : table.getAllSymbols()) {
            if (!symbol.getType() && Memory.inTextSegment(symbol.getAddress())) {
                return symbol;
            }
        }
        return null;
    }

    private static void applyFunctionCall(Symbol entry, FunctionSignature signature, long[] args) {
        int sentinel = Memory.textBaseAddress - Memory.WORD_LENGTH_BYTES;
        RegisterFile.initializeProgramCounter(entry.getAddress());
        RegisterFile.getRegister("ra").setValue(sentinel & 0xFFFFFFFFL);

        int registerArgs = Math.min(args.length, MAX_REGISTER_ARGS);
        for (int i = 0; i < registerArgs; i++) {
            Register argRegister = RegisterFile.getRegister("a" + i);
            if (argRegister != null) {
                argRegister.setValue(args[i]);
            }
        }

        // Arguments beyond a0..a7 are passed on the stack per the calling convention.
        if (args.length > MAX_REGISTER_ARGS) {
            int stackArgs = args.length - MAX_REGISTER_ARGS;
            Register sp = RegisterFile.getRegister("sp");
            long newSp = (sp.getValue() & 0xFFFFFFFFL) - 8L * stackArgs;
            try {
                for (int i = 0; i < stackArgs; i++) {
                    Globals.memory.setDoubleWord((int) (newSp + 8L * i), args[MAX_REGISTER_ARGS + i]);
                }
                sp.setValue(newSp);
            } catch (AddressErrorException e) {
                // Stack writes below the stack limit should not happen for
                // reasonable argument counts; ignore and keep register args.
            }
        }

        FunctionRun.activate(entry.getName(),
                signature != null && signature.hasReturnValue(),
                args, sentinel);
    }

    // -----------------------------------------------------------------------
    // User input
    // -----------------------------------------------------------------------

    private static long[] promptForArguments(Window parent, String functionName, FunctionSignature signature) {
        if (signature != null) {
            return promptNamedArguments(parent, functionName, signature);
        }
        return promptFreeFormArguments(parent, functionName);
    }

    private static long[] promptNamedArguments(Window parent, String functionName, FunctionSignature signature) {
        List<JTextField> fields = new ArrayList<>();
        while (true) {
            JPanel panel = new JPanel(new GridBagLayout());
            GridBagConstraints c = new GridBagConstraints();
            c.anchor = GridBagConstraints.LINE_START;
            c.gridwidth = GridBagConstraints.REMAINDER;
            c.insets = new Insets(4, 4, 4, 4);
            panel.add(new JLabel("<html><b>The program has no 'main' or '_start' label.<br>"
                    + "The function '" + functionName + "' will be executed.</b></html>"), c);
            if (!signature.paramNames.isEmpty()) {
                panel.add(new JLabel("Enter integer values for all parameters:"), c);
                if (fields.isEmpty()) {
                    for (int i = 0; i < signature.paramNames.size(); i++) {
                        JTextField field = new JTextField(10);
                        fields.add(field);
                        JPanel row = new JPanel(new FlowLayout(FlowLayout.LEFT));
                        row.add(new JLabel(signature.paramNames.get(i) + " ="));
                        row.add(field);
                        panel.add(row, c);
                    }
                } else {
                    for (int i = 0; i < signature.paramNames.size(); i++) {
                        JPanel row = new JPanel(new FlowLayout(FlowLayout.LEFT));
                        row.add(new JLabel(signature.paramNames.get(i) + " ="));
                        row.add(fields.get(i));
                        panel.add(row, c);
                    }
                }
            } else {
                panel.add(new JLabel("The function takes no parameters."), c);
            }
            int option = JOptionPane.showConfirmDialog(parent, panel,
                    "Arguments for function '" + functionName + "'",
                    JOptionPane.OK_CANCEL_OPTION, JOptionPane.PLAIN_MESSAGE);
            if (option != JOptionPane.OK_OPTION) {
                return null;
            }
            if (signature.paramNames.isEmpty()) {
                return new long[0];
            }
            long[] values = new long[signature.paramNames.size()];
            boolean valid = true;
            for (int i = 0; i < fields.size(); i++) {
                String text = fields.get(i).getText().trim();
                if (text.isEmpty()) {
                    showInvalidArgumentsError(parent, "Parameter '" + signature.paramNames.get(i)
                            + "' is required.");
                    valid = false;
                    break;
                }
                try {
                    values[i] = parseLong(text);
                } catch (NumberFormatException e) {
                    showInvalidArgumentsError(parent, "'" + text + "' is not a valid integer value for parameter '"
                            + signature.paramNames.get(i) + "'.");
                    valid = false;
                    break;
                }
            }
            if (valid) {
                return values;
            }
        }
    }

    private static long[] promptFreeFormArguments(Window parent, String functionName) {
        String current = "";
        while (true) {
            Object input = JOptionPane.showInputDialog(parent,
                    "<html>The program has no 'main' or '_start' label.<br>"
                            + "The function '" + functionName + "' will be executed.<br>"
                            + "Enter integer arguments separated by whitespace (may be empty):</html>",
                    "Arguments for function '" + functionName + "'",
                    JOptionPane.QUESTION_MESSAGE, null, null, current);
            if (input == null) {
                return null; // cancelled
            }
            current = input.toString();
            String trimmed = current.trim();
            if (trimmed.isEmpty()) {
                return new long[0];
            }
            String[] tokens = trimmed.split("\\s+");
            if (tokens.length > MAX_REGISTER_ARGS + DEFAULT_STACK_SLOTS) {
                showInvalidArgumentsError(parent, "Too many arguments (maximum "
                        + (MAX_REGISTER_ARGS + DEFAULT_STACK_SLOTS) + ").");
                continue;
            }
            long[] values = new long[tokens.length];
            boolean valid = true;
            for (int i = 0; i < tokens.length; i++) {
                try {
                    values[i] = parseLong(tokens[i]);
                } catch (NumberFormatException e) {
                    showInvalidArgumentsError(parent, "'" + tokens[i] + "' is not a valid integer value.");
                    valid = false;
                    break;
                }
            }
            if (valid) {
                return values;
            }
        }
    }

    private static void showInvalidArgumentsError(Window parent, String message) {
        JOptionPane.showMessageDialog(parent, message,
                "Invalid arguments", JOptionPane.ERROR_MESSAGE);
    }

    private static long parseLong(String text) throws NumberFormatException {
        // Long.decode supports decimal, 0x hex and 0 octal, with optional sign.
        return Long.decode(text.trim());
    }

    private static String joinArguments(long[] args) {
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < args.length; i++) {
            if (i > 0) {
                sb.append(", ");
            }
            sb.append(args[i]);
        }
        return sb.toString();
    }

    // -----------------------------------------------------------------------
    // Signature lookup and parsing
    // -----------------------------------------------------------------------

    /**
     * Looks for a C function signature in a comment line ('#' or '//')
     * directly preceding the label definition, searching the assembled source
     * files in order.
     *
     * @return parsed signature, or null if not found / unparsable / the name
     *         in the signature does not match the label.
     */
    private static FunctionSignature findSignature(String label) {
        ArrayList<RISCVprogram> programs = RunAssembleAction.getProgramsToAssemble();
        if (programs == null) {
            return null;
        }
        Pattern labelPattern = Pattern.compile("^\\s*" + Pattern.quote(label) + "\\s*:");
        for (RISCVprogram program : programs) {
            String filename = program.getFilename();
            if (filename == null) {
                continue;
            }
            List<String> lines;
            try {
                lines = Files.readAllLines(Paths.get(filename));
            } catch (IOException e) {
                continue;
            }
            for (int i = 0; i < lines.size(); i++) {
                if (!labelPattern.matcher(lines.get(i)).find()) {
                    continue;
                }
                // The signature comment must be the nearest non-blank line above the label.
                for (int j = i - 1; j >= 0; j--) {
                    String previous = lines.get(j).trim();
                    if (previous.isEmpty()) {
                        continue;
                    }
                    if (previous.startsWith("//")) {
                        return parseSignature(previous.substring(2), label);
                    }
                    if (previous.startsWith("#")) {
                        return parseSignature(previous.substring(1), label);
                    }
                    break; // nearest non-blank line is not a comment
                }
                return null;
            }
        }
        return null;
    }

    private static final Pattern TRAILING_IDENTIFIER = Pattern.compile("([A-Za-z_]\\w*)\\s*$");

    /**
     * Parses a C signature like "int64_t poly(int64_t a, int64_t b)".
     */
    private static FunctionSignature parseSignature(String body, String label) {
        int open = body.indexOf('(');
        if (open < 0) {
            return null;
        }
        int close = body.lastIndexOf(')');
        if (close < open) {
            return null;
        }
        String before = body.substring(0, open).trim();
        String params = body.substring(open + 1, close).trim();
        if (before.isEmpty()) {
            return null;
        }
        Matcher nameMatcher = TRAILING_IDENTIFIER.matcher(before);
        if (!nameMatcher.find()) {
            return null;
        }
        String name = nameMatcher.group(1);
        if (!name.equals(label)) {
            return null; // comment describes some other function
        }
        String returnType = before.substring(0, nameMatcher.start()).replaceAll("[*&\\s]+", "").trim();

        List<String> paramNames = new ArrayList<>();
        if (!params.isEmpty()) {
            for (String param : params.split(",")) {
                String normalized = param.replace("[", " ").replace("]", " ").trim();
                if (normalized.isEmpty()) {
                    return null;
                }
                Matcher paramMatcher = TRAILING_IDENTIFIER.matcher(normalized);
                if (!paramMatcher.find()) {
                    return null;
                }
                paramNames.add(paramMatcher.group(1));
            }
        }
        return new FunctionSignature(name, returnType, paramNames);
    }
}
