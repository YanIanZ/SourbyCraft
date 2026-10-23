package dev.iyanz.sourbycraft.api.profile;

/**
 * An exception carrying a {@link ProfileErrorCode}, for operations whose failure is not a normal
 * result (create, delete, save).
 */
public class ProfileOperationException extends RuntimeException {

    private static final long serialVersionUID = 1L;

    private final ProfileErrorCode code;

    public ProfileOperationException(final ProfileErrorCode code, final String message) {
        super(message);
        this.code = code;
    }

    public ProfileOperationException(final ProfileErrorCode code, final String message, final Throwable cause) {
        super(message, cause);
        this.code = code;
    }

    public ProfileErrorCode code() {
        return this.code;
    }
}
