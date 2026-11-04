package dev.iyanz.sourbycraft.api.profile.event;

import dev.iyanz.sourbycraft.api.profile.ProfileInfo;
import java.util.Objects;
import org.bukkit.event.Event;
import org.bukkit.event.HandlerList;
import org.jetbrains.annotations.ApiStatus;
import org.jspecify.annotations.NullMarked;

/**
 * Added in Build 55. A profile was created and is durable: the profile as {@code createProfile} returned it. Never
 * fired when the creation was refused or failed. Its name and type are available from
 * {@code AuroraProfiles.getProfileDetails(getProfile().id())}.
 *
 * <p><b>Thread.</b> Asynchronous, from a thread of the profile service, never a region, entity, global-tick or network
 * thread; see the {@linkplain dev.iyanz.sourbycraft.api.profile.event package description}. Not cancellable.</p>
 */
@NullMarked
public class ProfileCreatedEvent extends Event {

    private static final HandlerList HANDLERS = new HandlerList();

    private final ProfileInfo profile;

    @ApiStatus.Internal
    public ProfileCreatedEvent(final ProfileInfo profile) {
        super(true);
        this.profile = Objects.requireNonNull(profile, "profile");
    }

    public static HandlerList getHandlerList() {
        return HANDLERS;
    }

    @Override
    public HandlerList getHandlers() {
        return HANDLERS;
    }

    /** The new profile. */
    public ProfileInfo getProfile() {
        return this.profile;
    }
}
