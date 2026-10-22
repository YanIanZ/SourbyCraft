package dev.iyanz.sourbycraft.world;

import java.util.function.Consumer;
import net.minecraft.network.Connection;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ClientInformation;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import org.bukkit.Location;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Spigot's {@code PlayerSpawnLocationEvent} under region threading, owned by SourbyCraft. The
 * inherited baseline switched the event off ({@code if (false && ...)} in {@code PrepareSpawnTask},
 * ledger {@code PrepareSpawnTask#1}); feature patch "fire PlayerSpawnLocationEvent on the global
 * tick" calls in here instead, from {@code PrepareSpawnTask.Preparing#tick}:
 *
 * <ul>
 *   <li>Thread: the global tick, which ticks configuration-phase connections
 *       ({@code RegionizedServer#tickConnections}) — the thread that decides the join position. No
 *       region state is touched: the player object is not in any world yet (Paper creates it early
 *       for this event, as it does for the legacy {@code PlayerLoginEvent}).</li>
 *   <li>Once per join ({@link #shouldFireLegacy}), before Paper's {@code AsyncPlayerSpawnLocationEvent},
 *       which then sees the legacy result — Paper's order. The two are different events; each fires
 *       once.</li>
 *   <li>The chosen location feeds the existing async chunk load ({@code trackLoadWithRadius});
 *       placement still happens on the owning region after that load.</li>
 *   <li>If an event moved the join to another world, the engine's "player joining" mark (which
 *       holds a world's unload) moves with it ({@link #moveJoinMark}); a world that is unloading
 *       is refused and the join stays in the marked world.</li>
 * </ul>
 */
public final class SpawnLocationEvents {

    private static final Logger LOGGER = LoggerFactory.getLogger("SourbyCraft/Join");

    private SpawnLocationEvents() {}

    /** Fire the legacy event: not yet fired for this join, and somebody listens. */
    public static boolean shouldFireLegacy(final boolean alreadyFired, final int listeners) {
        return !alreadyFired && listeners > 0;
    }

    /**
     * Fires {@code PlayerSpawnLocationEvent} with the early player Paper keeps on the connection
     * ({@code savedPlayerForLegacyEvents}) and returns the event's location (mutable by listeners).
     * Mirrors Paper's block that the baseline disabled.
     */
    public static Location fireLegacy(final Connection connection, final MinecraftServer server,
                                      final com.mojang.authlib.GameProfile profile, final Location proposed) {
        ServerPlayer player = connection.savedPlayerForLegacyEvents;
        if (player == null) {
            player = new ServerPlayer(server, server.overworld(), profile, ClientInformation.createDefault());
            connection.savedPlayerForLegacyEvents = player;
        }
        final org.spigotmc.event.player.PlayerSpawnLocationEvent event =
            new org.spigotmc.event.player.PlayerSpawnLocationEvent(player.getBukkitEntity(), proposed);
        event.callEvent();
        final Location chosen = event.getSpawnLocation();
        if (chosen.getWorld() == null) {
            chosen.setWorld(proposed.getWorld());
        }
        return chosen;
    }

    /** The engine's per-world "player joining" marks. */
    public interface JoinMarks<L> {
        /** Marks {@code player} as joining {@code level}; {@code false} (and no mark) when the level is unloading. */
        boolean markIfLoaded(L level, String player);

        void unmark(L level, String player);
    }

    /**
     * The level the join proceeds in. When {@code chosen} differs from {@code marked}, the mark is
     * added to {@code chosen} first and only then removed from {@code marked}, so the join is never
     * unmarked in between; an unloading {@code chosen} is refused (warned) and {@code marked} kept.
     */
    public static <L> L moveJoinMark(final L marked, final L chosen, final String player, final JoinMarks<L> marks,
                                     final Consumer<String> warn) {
        if (chosen == marked) {
            return marked;
        }
        if (!marks.markIfLoaded(chosen, player)) {
            warn.accept("A spawn-location event sent joining player " + player + " to world " + chosen
                + ", which is unloading; the player joins world " + marked + " at the chosen coordinates instead.");
            return marked;
        }
        marks.unmark(marked, player);
        return chosen;
    }

    /** {@link #moveJoinMark} over the engine's {@code ServerLevel} marks. */
    public static ServerLevel moveJoinMark(final ServerLevel marked, final ServerLevel chosen, final String player) {
        return moveJoinMark(marked, chosen, player, SERVER_LEVEL_MARKS, LOGGER::warn);
    }

    private static final JoinMarks<ServerLevel> SERVER_LEVEL_MARKS = new JoinMarks<>() {
        @Override
        public boolean markIfLoaded(final ServerLevel level, final String player) {
            level.canvas$obtainWorldStageLock();
            try {
                if (level.canvas$unloadTicket.isPresent()) {
                    return false;
                }
                level.canvas$incrementJoiningPlayers(player);
                return true;
            } finally {
                level.canvas$releaseWorldStageLock();
            }
        }

        @Override
        public void unmark(final ServerLevel level, final String player) {
            level.canvas$decrementJoiningPlayers(player);
        }

        @Override
        public String toString() {
            return "ServerLevel join marks";
        }
    };
}
