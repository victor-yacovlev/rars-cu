package rars.simulator;

import rars.riscv.hardware.Memory;

/**
 * State for "function run" mode. When the assembled program has no global
 * 'main' or '_start' label, execution starts at the first global text symbol
 * with argument values supplied by the user through a dialog.
 *
 * The return address register (ra) is planted with a sentinel address located
 * just below the text segment. When the function executes 'ret' the program
 * counter becomes the sentinel; the simulator checks for it and terminates
 * normally instead of faulting with an instruction load access error.
 */
public class FunctionRun {
    /**
     * Sentinel address planted into ra; reaching it in function-run mode
     * terminates the simulation normally.
     */
    public static volatile int returnAddress = Memory.textBaseAddress - Memory.WORD_LENGTH_BYTES;

    /**
     * True while a run session configured for a function entry point is
     * in progress (between assembly/reset and termination).
     */
    public static volatile boolean active = false;

    /**
     * Name of the function being executed.
     */
    public static volatile String functionName = null;

    /**
     * Argument values entered by the user (kept for the result report).
     */
    public static volatile long[] arguments = new long[0];

    /**
     * Whether the function returns a value: per the signature, or assumed
     * integer when no signature is present.
     */
    public static volatile boolean hasReturnValue = false;

    public static void activate(String name, boolean hasReturnValue, long[] args, int returnAddress) {
        FunctionRun.functionName = name;
        FunctionRun.hasReturnValue = hasReturnValue;
        FunctionRun.arguments = (args == null) ? new long[0] : args.clone();
        FunctionRun.returnAddress = returnAddress;
        FunctionRun.active = true;
    }

    public static void clear() {
        active = false;
        functionName = null;
        arguments = new long[0];
        hasReturnValue = false;
        returnAddress = Memory.textBaseAddress - Memory.WORD_LENGTH_BYTES;
    }
}
