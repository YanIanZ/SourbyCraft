package dev.iyanz.sourbycraft.api.profile.event;

import java.util.Objects;
import java.util.UUID;
import org.bukkit.event.Event;
import org.bukkit.event.HandlerList;
import org.jetbrains.annotations.ApiStatus;
import org.jspecify.annotations.NullMarked;

/**
 * Added in Build 55. A profile was deleted and the deletion is durable: it is marked deleted and its snapshots and
 * bindings are removed ({@code deleteProfile}; its AWF worlds are never deleted by this). Never fired when the deletion
 * was refused, failed, or found the profile already deleted.
 *
 * <p><b>Thread.</b> Asynchronous, from a thread of the profile service, never a region, entity, global-tick or network
 * thread; see the {@linkplain dev.iyanz.sourbycraft.api.profile.event package description}. Not cancellable.</p>
 */
@NullMarked
public class ProfileDeletedEvent extends Event {

    private static final HandlerList HANDLERS = new HandlerList();

    private final UUID profileId;
    private final UUID accountId;

    /**
     * @param profileId the deleted profile
     * @param accountId the account that owned it
     */
    @ApiStatus.Internal
    public ProfileDeletedEvent(final UUID profileId, final UUID accountId) {
        super(true);
        this.profileId = Objects.requireNonNull(profileId, "profileId");
        this.accountId = Objects.requireNonNull(accountId, "accountId");
    }

    public static HandlerList getHandlerList() {
        return HANDLERS;
    }

    @Override
    public HandlerList getHandlers() {
        return HANDLERS;
    }

    /** The deleted profile. */
    public UUID getProfileId() {
        return this.profileId;
    }

    /** The account that owned the profile. */
    public UUID getAccountId() {
        return this.accountId;
    }
}
