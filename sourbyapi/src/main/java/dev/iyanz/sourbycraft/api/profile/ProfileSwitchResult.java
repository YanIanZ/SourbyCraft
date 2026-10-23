package dev.iyanz.sourbycraft.api.profile;

import java.util.Objects;
import java.util.UUID;
import org.jspecify.annotations.Nullable;

/**
 * The outcome of a switch. A failed switch always names a {@link ProfileErrorCode}; the player stays
 * on the profile that was active before, or the transition journal reports that recovery is pending.
 *
 * @param success whether the switch committed
 * @param errorCode the reason when it did not; {@code null} on success
 * @param message a human-readable detail, safe to log (no inventory contents)
 * @param profile the target profile on success, otherwise the profile that remains active (may be null)
 * @param transitionId the journal id, for {@code /awf multiprofile recover}
 * @param durationMillis wall time of the attempt
 */
public record ProfileSwitchResult(boolean success, @Nullable ProfileErrorCode errorCode, String message,
                                  @Nullable ProfileInfo profile, @Nullable UUID transitionId,
                                  long durationMillis) {

    public ProfileSwitchResult {
        Objects.requireNonNull(message, "message");
        if (success && errorCode != null) throw new IllegalArgumentException("a successful switch has no error code");
    }

    public static ProfileSwitchResult success(final ProfileInfo profile, final java.util.UUID transitionId,
                                              final long durationMillis) {
        return new ProfileSwitchResult(true, null, "switched to " + profile.id(), profile, transitionId, durationMillis);
    }

    public static ProfileSwitchResult failure(final ProfileErrorCode code, final String message, final ProfileInfo profile,
                                              final java.util.UUID transitionId, final long durationMillis) {
        return new ProfileSwitchResult(false, code, message, profile, transitionId, durationMillis);
    }
}
