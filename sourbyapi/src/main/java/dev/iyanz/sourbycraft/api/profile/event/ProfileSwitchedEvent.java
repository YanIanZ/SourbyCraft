package dev.iyanz.sourbycraft.api.profile.event;

import dev.iyanz.sourbycraft.api.profile.ProfileInfo;
import dev.iyanz.sourbycraft.api.profile.ProfileSwitchResult;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import org.bukkit.event.Event;
import org.bukkit.event.HandlerList;
import org.jetbrains.annotations.ApiStatus;
import org.jspecify.annotations.NullMarked;
import org.jspecify.annotations.Nullable;

/**
 * Added in Build 55. A profile switch committed: the player's active profile is now {@link #getProfile()}, durably.
 * Fired once per committed switch, after the commit and never for a refused or failed switch or one whose outcome is
 * {@link dev.iyanz.sourbycraft.api.profile.ProfileErrorCode#UNKNOWN_OUTCOME}. The player may already have left or
 * switched again by the time a handler runs.
 *
 * <p><b>Thread.</b> Asynchronous, from a thread of the profile service, never a region, entity, global-tick or network
 * thread; see the {@linkplain dev.iyanz.sourbycraft.api.profile.event package description}. Not cancellable.</p>
 */
@NullMarked
public class ProfileSwitchedEvent extends Event {

    private static final HandlerList HANDLERS = new HandlerList();

    private final UUID playerId;
    private final @Nullable UUID fromProfileId;
    private final ProfileSwitchResult result;

    /**
     * @param playerId the player who switched
     * @param fromProfileId the profile active before the switch, or {@code null} when none was
     * @param result the committed result; must be {@linkplain ProfileSwitchResult#success() successful} and name its
     *     profile
     */
    @ApiStatus.Internal
    public ProfileSwitchedEvent(final UUID playerId, final @Nullable UUID fromProfileId, final ProfileSwitchResult result) {
        super(true);
        this.playerId = Objects.requireNonNull(playerId, "playerId");
        this.fromProfileId = fromProfileId;
        this.result = Objects.requireNonNull(result, "result");
        if (!result.success() || result.profile() == null) {
            throw new IllegalArgumentException("ProfileSwitchedEvent reports committed switches only");
        }
    }

    public static HandlerList getHandlerList() {
        return HANDLERS;
    }

    @Override
    public HandlerList getHandlers() {
        return HANDLERS;
    }

    /** The player who switched. */
    public UUID getPlayerId() {
        return this.playerId;
    }

    /** The profile active before the switch; empty when the player had none. */
    public Optional<UUID> getFromProfileId() {
        return Optional.ofNullable(this.fromProfileId);
    }

    /** The profile now active, as the switch committed it. */
    public ProfileInfo getProfile() {
        return Objects.requireNonNull(this.result.profile());
    }

    /** The full result, as {@code switchProfile} returned it (transition id, duration). */
    public ProfileSwitchResult getResult() {
        return this.result;
    }
}
