package dev.iyanz.sourbycraft.awf;

import java.io.IOException;

/**
 * A backend call or a commit stopped by the AWF shutdown deadline ({@code aurora.awf.shutdown-deadline-ms}).
 *
 * <p>{@link #requestSent()} is what separates the two outcomes that must never be confused: when
 * {@code false} nothing reached the backend, so the operation certainly did not happen; when
 * {@code true} the request (or part of it) may have reached the backend before the connection was
 * cut, so it may or may not have been applied. A caller never reports the second case as a failed
 * commit, only as an unconfirmed one.</p>
 */
public class AwfDeadlineExceeded extends IOException {
    private static final long serialVersionUID = 1L;
    private final boolean requestSent;

    public AwfDeadlineExceeded(final String message, final boolean requestSent) {
        super(message);
        this.requestSent = requestSent;
    }

    public AwfDeadlineExceeded(final String message, final boolean requestSent, final Throwable cause) {
        super(message, cause);
        this.requestSent = requestSent;
    }

    /** Whether the request may have reached the backend, so its outcome is unknown. */
    public boolean requestSent() {
        return this.requestSent;
    }
}
