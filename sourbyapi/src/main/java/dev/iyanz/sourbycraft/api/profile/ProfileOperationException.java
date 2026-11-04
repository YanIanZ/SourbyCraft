package dev.iyanz.sourbycraft.api.profile;

import java.util.UUID;
import org.jspecify.annotations.Nullable;

/**
 * An exception carrying a {@link ProfileErrorCode}, for operations whose failure is not a normal
 * result (create, delete, save).
 *
 * <p>Since Build 54 a failure of a ledger, salvage or co-op operation that concerns one operation (notably
 * {@link ProfileErrorCode#UNKNOWN_OUTCOME}) names it in {@link #operationId()}, so a consumer can reconcile it
 * through {@code AuroraProfiles.bankReceipt} instead of repeating the request.</p>
 */
public class ProfileOperationException extends RuntimeException {

    private static final long serialVersionUID = 2L;

    private final ProfileErrorCode code;
    private final @Nullable UUID operationId;

    public ProfileOperationException(final ProfileErrorCode code, final String message) {
        super(message);
        this.code = code;
        this.operationId = null;
    }

    public ProfileOperationException(final ProfileErrorCode code, final String message, final Throwable cause) {
        super(message, cause);
        this.code = code;
        this.operationId = null;
    }

    /** Added in Build 54: an error about one operation, named by its id. */
    public ProfileOperationException(final ProfileErrorCode code, final String message, final @Nullable UUID operationId) {
        super(message);
        this.code = code;
        this.operationId = operationId;
    }

    public ProfileErrorCode code() {
        return this.code;
    }

    /**
     * Added in Build 54. The operation this failure concerns (the one the caller supplied, or the one the server minted),
     * or {@code null} when the failure is not about one operation. For {@link ProfileErrorCode#UNKNOWN_OUTCOME} the
     * consumer passes it to {@code AuroraProfiles.bankReceipt} to learn whether the operation committed.
     */
    public @Nullable UUID operationId() {
        return this.operationId;
    }
}
