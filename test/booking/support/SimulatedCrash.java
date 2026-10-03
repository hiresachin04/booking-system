package booking.support;

/**
 * Stands in for the process dying mid-call. An Error rather than an exception, so it escapes the code's own
 * exception handling exactly as a crash would: nothing after the throw point runs, and nothing tidies up.
 */
public final class SimulatedCrash extends Error {
    public SimulatedCrash(String where) {
        super("simulated crash " + where);
    }
}
