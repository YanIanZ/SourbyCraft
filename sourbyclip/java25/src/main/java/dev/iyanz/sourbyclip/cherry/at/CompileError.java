package dev.iyanz.sourbyclip.cherry.at;

/** Cherry — thrown when an access-transformer line cannot be parsed. Ported from Horizon. */
public class CompileError extends RuntimeException {

    /** @param message a human-readable description of why the AT line failed to parse */
    public CompileError(String message) {
        super(message);
    }
}
