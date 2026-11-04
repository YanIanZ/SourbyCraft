package dev.iyanz.sourbycraft.api.profile;

import java.util.UUID;
import org.jspecify.annotations.NullMarked;
import org.jspecify.annotations.Nullable;

/**
 * A consumer hook that may veto a profile switch before the player's state is touched (Build 55): combat,
 * a dungeon run, an open trade, a pending auction. Registered through
 * {@link AuroraProfiles#registerSwitchGuard(org.bukkit.plugin.Plugin, ProfileSwitchGuard)}.
 *
 * <p><b>When it runs.</b> After the server's own admission checks (membership, type rules, lock/archive status,
 * cooldown, no switch in progress) and after the target profile's world is ready, and <em>before</em> the player
 * is frozen and captured: a veto leaves the player exactly as they were, nothing is written, and the switch
 * completes with {@link ProfileErrorCode#SWITCH_VETOED} carrying the reason. A veto never consumes the cooldown.</p>
 *
 * <p><b>Threading and bounds.</b> Guards are called off every region, entity, global-tick and Netty thread, one
 * after the other, with one time budget for all of them ({@code 2000 ms}); a guard that throws or does not answer
 * in time counts as a veto and is logged with a WARN. A guard must therefore be thread-safe and must not block
 * on game threads: read a concurrent map or an atomic you maintain from your own listeners; do not call Bukkit
 * entity or world APIs from here. A guard must not start another switch for the same player: the running switch
 * holds the player's reservation and such a call is refused {@link ProfileErrorCode#SWITCH_IN_PROGRESS}.</p>
 *
 * <p>At most {@value AuroraProfiles#MAX_SWITCH_GUARDS} guards are registered on one server; a plugin's guards are
 * removed when it unregisters them or is disabled.</p>
 */
@FunctionalInterface
@NullMarked
public interface ProfileSwitchGuard {

    /**
     * @param playerId the switching player (also the account)
     * @param fromProfileId the profile being left, or {@code null} on the player's first switch
     * @param toProfileId the target profile
     * @return {@code null} to allow the switch, or a short player-safe reason (at most 128 characters are kept)
     *     to refuse it
     */
    @Nullable String refusal(UUID playerId, @Nullable UUID fromProfileId, UUID toProfileId);
}
