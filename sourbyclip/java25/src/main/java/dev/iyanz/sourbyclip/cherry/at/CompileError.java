package dev.iyanz.sourbyclip.cherry.at;

/** Cherry — thrown when an access-transformer line cannot be parsed. Ported from Horizon. */
public class CompileError extends RuntimeException {
    public CompileError(String message) {
        super(message);
    }
}
