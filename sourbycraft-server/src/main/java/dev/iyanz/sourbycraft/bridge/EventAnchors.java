package dev.iyanz.sourbycraft.bridge;

import org.bukkit.entity.HumanEntity;
import org.bukkit.entity.Player;
import org.bukkit.event.Event;
import org.bukkit.event.block.BlockBreakEvent;
import org.bukkit.event.block.BlockPlaceEvent;
import org.bukkit.event.block.SignChangeEvent;
import org.bukkit.event.entity.EntityDeathEvent;
import org.bukkit.event.entity.EntityEvent;
import org.bukkit.event.entity.EntityRemoveEvent;
import org.bukkit.event.entity.PlayerDeathEvent;
import org.bukkit.event.inventory.InventoryEvent;
import org.bukkit.event.player.PlayerEvent;
import org.bukkit.event.player.PlayerKickEvent;
import org.bukkit.event.player.PlayerLoginEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.inventory.InventoryView;
import org.spigotmc.event.player.PlayerSpawnLocationEvent;

/**
 * Which player, if any, an event is about, for {@link SchedulingContext}.
 *
 * <p>Players only. A mob dies often and without notice; a legacy task anchored to it would be retired
 * (never run) when it does, and a silently dropped task is a worse surprise than a task on the global
 * region. A player is stable while connected, and the events that mean "this player is leaving"
 * ({@link PlayerQuitEvent}, {@link PlayerKickEvent}) or "this player is not in a world yet"
 * ({@link PlayerLoginEvent}, {@link PlayerSpawnLocationEvent}) anchor nothing and also hide the
 * enclosing frame's player ({@link #CLEAR}): a task scheduled from a quit handler must run (cleanup),
 * the entity scheduler of a player who is gone or not yet placed would retire or hold it, and a kick
 * fired inside another player's command ({@code /kick X}) must not tie the handler's cleanup to the
 * kicker, whose leaving would cancel it.</p>
 */
final class EventAnchors {

    /** {@link #anchor}: publish no anchor for this event's listeners, not even the enclosing frame's. */
    static final Object CLEAR = new Object() {
        @Override public String toString() { return "EventAnchors.CLEAR"; }
    };

    private EventAnchors() {}

    /** The player the event is about, or {@code null}. Never throws. */
    static Player resolve(final Event event) {
        return anchor(event) instanceof Player player ? player : null;
    }

    /**
     * What the event publishes to its listeners: the player it is about, {@link #CLEAR} for a leaving or
     * not-yet-placed player, or {@code null} to keep the frame it was fired in (an event about no player).
     * Never throws.
     */
    static Object anchor(final Event event) {
        if (event == null) return null;
        try {
            return resolve0(event);
        } catch (final RuntimeException unresolvable) {
            return null;
        }
    }

    private static Object resolve0(final Event event) {
        if (event instanceof PlayerEvent player) {
            if (event instanceof PlayerQuitEvent || event instanceof PlayerKickEvent
                || event instanceof PlayerLoginEvent || event instanceof PlayerSpawnLocationEvent) {
                return CLEAR;
            }
            return player.getPlayer();
        }
        if (event instanceof PlayerDeathEvent death) {
            // The player respawns; the Bukkit entity and its scheduler stay.
            return death.getEntity();
        }
        if (event instanceof EntityEvent entity) {
            if (event instanceof EntityDeathEvent || event instanceof EntityRemoveEvent
                || event instanceof com.destroystokyo.paper.event.entity.EntityRemoveFromWorldEvent) {
                return null;
            }
            return entity.getEntity() instanceof Player player ? player : null;
        }
        if (event instanceof InventoryEvent inventory) {
            final InventoryView view = inventory.getView();
            final HumanEntity viewer = view == null ? null : view.getPlayer();
            return viewer instanceof Player player ? player : null;
        }
        if (event instanceof BlockBreakEvent broken) return broken.getPlayer();
        if (event instanceof BlockPlaceEvent placed) return placed.getPlayer();
        if (event instanceof SignChangeEvent sign) return sign.getPlayer();
        return null;
    }
}
