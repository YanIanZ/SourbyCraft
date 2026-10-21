package dev.iyanz.sourbycraft.guards;

import java.util.function.Consumer;
import net.minecraft.world.scores.PlayerTeam;
import net.minecraft.world.scores.Scoreboard;
import org.jspecify.annotations.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * The scoreboard half of {@code collisions.enable-player-collisions: false}. Paper keeps players out
 * of each other's way on the server through {@code EntitySelector.pushableBy}, and puts them all in
 * a hidden team with collision rule NEVER so clients predict the same thing. The base turned the
 * team handling off because scoreboards belong to the global tick and players join and leave on
 * their own region. Here the team changes are posted to the global tick (in submission order, so a
 * join followed by a quit leaves the player out of the team) and re-check their conditions where
 * they run.
 *
 * <p>Two Paper details are corrected rather than copied: removing a player on quit looks the team up
 * by the player (Paper passed the team's name to {@code getPlayersTeam}, which takes a holder name
 * and therefore never found it), and shutdown removes the team by name for the same reason.</p>
 *
 * <p>With the setting at its default ({@code collideRuleTeamName == null}) none of this runs.</p>
 */
public final class PlayerCollisionTeam {

    private static final Logger LOGGER = LoggerFactory.getLogger("SourbyCraft");

    private PlayerCollisionTeam() {
    }

    /** Engine glue: a player finished joining. {@code globalTick} posts a task to the global tick (supplied by the hook). */
    public static void join(final net.minecraft.server.players.PlayerList players, final net.minecraft.server.level.ServerPlayer player,
                            final Consumer<Runnable> globalTick) {
        final String teamName = players.collideRuleTeamName;
        if (teamName == null) {
            return;
        }
        join(scoreboard(players), teamName, player.getScoreboardName(), globalTick);
    }

    /** Engine glue: a player is leaving. At shutdown the posted task never runs; {@link #shutdown} removes the whole team. */
    public static void quit(final net.minecraft.server.players.PlayerList players, final net.minecraft.server.level.ServerPlayer player,
                            final Consumer<Runnable> globalTick) {
        final String teamName = players.collideRuleTeamName;
        if (teamName == null) {
            return;
        }
        quit(scoreboard(players), teamName, player.getScoreboardName(), globalTick);
    }

    /** Engine glue: the server is stopping; runs on the shutdown thread, where scoreboard access is allowed. */
    public static void shutdown(final net.minecraft.server.players.PlayerList players) {
        final String teamName = players.collideRuleTeamName;
        if (teamName == null) {
            return;
        }
        try {
            removeTeam(scoreboard(players), teamName);
        } catch (final RuntimeException e) {
            LOGGER.warn("Could not remove the player collision team {} at shutdown; it is removed again at the next boot", teamName, e);
        }
    }

    private static Scoreboard scoreboard(final net.minecraft.server.players.PlayerList players) {
        return players.getServer().getLevel(net.minecraft.world.level.Level.OVERWORLD).getScoreboard();
    }

    /**
     * Puts {@code holder} in the collision team on the global tick unless it has a team by then. The add is
     * atomic ({@link Scoreboard#addPlayerToTeamIfAbsent}): a plugin that gives the player a team between our
     * look and our write keeps its team.
     */
    public static void join(final Scoreboard scoreboard, final @Nullable String teamName, final String holder, final Consumer<Runnable> globalTick) {
        if (teamName == null) {
            return;
        }
        globalTick.accept(() -> guarded("join", teamName, holder, () -> {
            final PlayerTeam team = scoreboard.getPlayerTeam(teamName);
            if (team != null) {
                scoreboard.addPlayerToTeamIfAbsent(holder, team);
            }
        }));
    }

    /**
     * Takes {@code holder} out of the collision team on the global tick, if it is in it. A plugin moving the
     * player to another team first is not an error: the removal is then simply not ours to do.
     */
    public static void quit(final Scoreboard scoreboard, final @Nullable String teamName, final String holder, final Consumer<Runnable> globalTick) {
        if (teamName == null) {
            return;
        }
        globalTick.accept(() -> guarded("quit", teamName, holder, () -> {
            final PlayerTeam team = scoreboard.getPlayersTeam(holder);
            if (team != null && teamName.equals(team.getName())) {
                try {
                    scoreboard.removePlayerFromTeam(holder, team); // atomic inside; refuses if the membership changed
                } catch (final IllegalStateException movedMeanwhile) {
                    // the player's team changed between the look and the removal; nothing of ours is left to remove
                }
            }
        }));
    }

    /** Nothing thrown here may escape a global-tick task, which the engine would treat as a server failure. */
    private static void guarded(final String what, final String teamName, final String holder, final Runnable task) {
        try {
            task.run();
        } catch (final RuntimeException e) {
            LOGGER.warn("Player collision team {} for {} failed (team {}); the player keeps whatever team it has", what, holder, teamName, e);
        }
    }

    /** Removes the collision team itself (shutdown path; the caller owns the scoreboard). */
    public static void removeTeam(final Scoreboard scoreboard, final @Nullable String teamName) {
        if (teamName == null) {
            return;
        }
        final PlayerTeam team = scoreboard.getPlayerTeam(teamName);
        if (team != null) {
            scoreboard.removePlayerTeam(team);
        }
    }
}
