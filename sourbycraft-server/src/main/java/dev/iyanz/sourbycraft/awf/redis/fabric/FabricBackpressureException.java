package dev.iyanz.sourbycraft.awf.redis.fabric;

import java.io.IOException;

/**
 * A bounded queue refused a publish instead of growing without limit.
 *
 * <p>This is a deliberate outcome, not a failure to retry on the same thread: the caller should
 * record the loss (a profile event that was not acknowledged) and let the durable state, which was
 * written before the event, remain the source of truth.</p>
 */
public final class FabricBackpressureException extends IOException {

    private static final long serialVersionUID = 1L;

    public FabricBackpressureException(final String message) {
        super(message);
    }
}
