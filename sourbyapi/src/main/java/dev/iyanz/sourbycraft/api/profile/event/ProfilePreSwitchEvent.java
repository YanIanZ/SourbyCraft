package dev.iyanz.sourbycraft.api.profile.event;

import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import org.bukkit.event.Event;
import org.bukkit.event.HandlerList;
import org.jetbrains.annotations.ApiStatus;
import org.jspecify.annotations.NullMarked;
import org.jspecify.annotations.Nullable;

/**
 * Added in Build 55. A profile switch was admitted and is about to change the player's state: every check that can
 * refuse it before the switch begins (membership, profile type rules, switch guards and cooldown) has passed.
 *
 * <p><b>Informational, not cancellable.</b> The switch does not wait for handlers and a handler cannot stop it. The
 * switch may still fail after
 * this event (for example when saving the current profile fails): a {@link ProfileSwitchedEvent} follows only when it
 * commits, and nothing follows a failure.</p>
 *
 * <p><b>Thread.</b> Asynchronous, from a thread of the profile service, never a region, entity, global-tick or network
 * thread; see the {@linkplain dev.iyanz.sourbycraft.api.profile.event package description}. Because the switch does
 * not wait, a handler may run after the switch has already finished.</p>
 */
@NullMarked
public class ProfilePreSwitchEvent extends Event {

    private static final HandlerList HANDLERS = new HandlerList();

    private final UUID playerId;
    private final @Nullable UUID fromProfileId;
    private final UUID toProfileId;
    private final @Nullable UUID transitionId;

    /**
     * @param playerId the switching player
     * @param fromProfileId the profile active before the switch, or {@code null} when none was
     * @param toProfileId the profile being switched to
     * @param transitionId the switch's transition journal id when it has one yet, else {@code null}
     */
    @ApiStatus.Internal
    public ProfilePreSwitchEvent(final UUID playerId, final @Nullable UUID fromProfileId, final UUID toProfileId,
                                 final @Nullable UUID transitionId) {
        super(true);
        this.playerId = Objects.requireNonNull(playerId, "playerId");
        this.fromProfileId = fromProfileId;
        this.toProfileId = Objects.requireNonNull(toProfileId, "toProfileId");
        this.transitionId = transitionId;
    }

    public static HandlerList getHandlerList() {
        return HANDLERS;
    }

    @Override
    public HandlerList getHandlers() {
        return HANDLERS;
    }

    /** The switching player. */
    public UUID getPlayerId() {
        return this.playerId;
    }

    /** The profile active before the switch; empty when the player had none. */
    public Optional<UUID> getFromProfileId() {
        return Optional.ofNullable(this.fromProfileId);
    }

    /** The profile being switched to. */
    public UUID getToProfileId() {
        return this.toProfileId;
    }

    /** The transition journal id ({@code /awf multiprofile recover}), when the switch has one at this point. */
    public Optional<UUID> getTransitionId() {
        return Optional.ofNullable(this.transitionId);
    }
}
