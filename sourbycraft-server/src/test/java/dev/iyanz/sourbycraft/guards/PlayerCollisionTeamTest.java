package dev.iyanz.sourbycraft.guards;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CyclicBarrier;
import java.util.concurrent.atomic.AtomicBoolean;
import net.minecraft.world.scores.PlayerTeam;
import net.minecraft.world.scores.Scoreboard;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class PlayerCollisionTeamTest {

    private static final String TEAM = "collideRule_1";
    private final Scoreboard scoreboard = new Scoreboard();
    private final List<Runnable> globalTick = new ArrayList<>();

    @BeforeEach
    void team() {
        this.scoreboard.addPlayerTeam(TEAM);
    }

    private void runGlobalTick() {
        final List<Runnable> now = new ArrayList<>(this.globalTick);
        this.globalTick.clear();
        now.forEach(Runnable::run);
    }

    @Test
    void joiningTouchesTheScoreboardOnlyWhenTheGlobalTickRuns() {
        PlayerCollisionTeam.join(this.scoreboard, TEAM, "alice", this.globalTick::add);
        assertNull(this.scoreboard.getPlayersTeam("alice"), "nothing on the calling (region) thread");
        assertEquals(1, this.globalTick.size());
        this.runGlobalTick();
        assertSame(this.scoreboard.getPlayerTeam(TEAM), this.scoreboard.getPlayersTeam("alice"));
    }

    @Test
    void aPlayerWhoAlreadyHasATeamKeepsIt() {
        final PlayerTeam red = this.scoreboard.addPlayerTeam("red");
        this.scoreboard.addPlayerToTeam("alice", red);
        PlayerCollisionTeam.join(this.scoreboard, TEAM, "alice", this.globalTick::add);
        this.runGlobalTick();
        assertSame(red, this.scoreboard.getPlayersTeam("alice"));
    }

    @Test
    void quittingRemovesThePlayerFromTheCollisionTeamOnly() {
        PlayerCollisionTeam.join(this.scoreboard, TEAM, "alice", this.globalTick::add);
        final PlayerTeam red = this.scoreboard.addPlayerTeam("red");
        this.scoreboard.addPlayerToTeam("bob", red);
        this.runGlobalTick();

        PlayerCollisionTeam.quit(this.scoreboard, TEAM, "alice", this.globalTick::add);
        PlayerCollisionTeam.quit(this.scoreboard, TEAM, "bob", this.globalTick::add);
        assertNotNull(this.scoreboard.getPlayersTeam("alice"), "still in until the global tick runs");
        this.runGlobalTick();
        assertNull(this.scoreboard.getPlayersTeam("alice"));
        assertSame(red, this.scoreboard.getPlayersTeam("bob"), "another team is not ours to touch");
    }

    @Test
    void aJoinFollowedByAQuitLeavesThePlayerOutEvenWhenBothWerePostedBeforeTheTickRan() {
        PlayerCollisionTeam.join(this.scoreboard, TEAM, "alice", this.globalTick::add);
        PlayerCollisionTeam.quit(this.scoreboard, TEAM, "alice", this.globalTick::add);
        assertEquals(2, this.globalTick.size());
        this.runGlobalTick();
        assertNull(this.scoreboard.getPlayersTeam("alice"));
    }

    @Test
    void theTeamBeingGoneByTheTimeTheTickRunsIsNotAnError() {
        PlayerCollisionTeam.join(this.scoreboard, TEAM, "alice", this.globalTick::add);
        this.scoreboard.removePlayerTeam(this.scoreboard.getPlayerTeam(TEAM));
        this.runGlobalTick();
        assertNull(this.scoreboard.getPlayersTeam("alice"));
    }

    @Test
    void withTheSettingAtItsDefaultNothingIsPosted() {
        PlayerCollisionTeam.join(this.scoreboard, null, "alice", this.globalTick::add);
        PlayerCollisionTeam.quit(this.scoreboard, null, "alice", this.globalTick::add);
        PlayerCollisionTeam.removeTeam(this.scoreboard, null);
        assertTrue(this.globalTick.isEmpty());
    }

    @Test
    void shutdownRemovesTheTeamByItsName() {
        PlayerCollisionTeam.removeTeam(this.scoreboard, TEAM);
        assertNull(this.scoreboard.getPlayerTeam(TEAM));
        PlayerCollisionTeam.removeTeam(this.scoreboard, TEAM); // already gone: no error
    }

    /** A scoreboard whose first team lookup of a holder is followed by a plugin moving it to "red", like a plugin on another thread. */
    private static final class InterleavingScoreboard extends Scoreboard {
        private final AtomicBoolean fired = new AtomicBoolean();
        private final String holder;

        InterleavingScoreboard(final String holder) {
            this.holder = holder;
        }

        @Override
        public PlayerTeam getPlayersTeam(final String name) {
            final PlayerTeam seen = super.getPlayersTeam(name);
            if (name.equals(this.holder) && seen != null && this.fired.compareAndSet(false, true)) {
                this.addPlayerToTeam(name, this.getPlayerTeam("red"));
            }
            return seen;
        }
    }

    @Test
    void aPluginMovingThePlayerBetweenTheLookAndTheRemovalDoesNotThrowOutOfTheGlobalTask() {
        final Scoreboard board = new InterleavingScoreboard("alice");
        final PlayerTeam collide = board.addPlayerTeam(TEAM);
        final PlayerTeam red = board.addPlayerTeam("red");
        board.addPlayerToTeam("alice", collide);
        PlayerCollisionTeam.quit(board, TEAM, "alice", this.globalTick::add);
        assertDoesNotThrow(this::runGlobalTick);
        assertSame(red, board.getPlayersTeam("alice"), "the plugin's team stands");
    }

    @Test
    void aFailingScoreboardNeverThrowsOutOfTheGlobalTask() {
        final Scoreboard broken = new Scoreboard() {
            @Override
            public boolean addPlayerToTeamIfAbsent(final String player, final PlayerTeam team) {
                throw new IllegalArgumentException("scoreboard exploded");
            }

            @Override
            public PlayerTeam getPlayersTeam(final String name) {
                throw new IllegalStateException("scoreboard exploded");
            }
        };
        broken.addPlayerTeam(TEAM);
        PlayerCollisionTeam.join(broken, TEAM, "alice", this.globalTick::add);
        PlayerCollisionTeam.quit(broken, TEAM, "alice", this.globalTick::add);
        assertDoesNotThrow(this::runGlobalTick);
    }

    @Test
    void theAtomicAddRefusesAHolderWithATeamAndAnUnregisteredTeam() {
        final PlayerTeam collide = this.scoreboard.getPlayerTeam(TEAM);
        final PlayerTeam red = this.scoreboard.addPlayerTeam("red");
        this.scoreboard.addPlayerToTeam("bob", red);
        assertFalse(this.scoreboard.addPlayerToTeamIfAbsent("bob", collide));
        assertSame(red, this.scoreboard.getPlayersTeam("bob"));
        assertTrue(this.scoreboard.addPlayerToTeamIfAbsent("alice", collide));
        assertTrue(collide.getPlayers().contains("alice"));
        this.scoreboard.removePlayerTeam(collide);
        assertFalse(this.scoreboard.addPlayerToTeamIfAbsent("carol", collide), "a team that is no longer registered");
        assertNull(this.scoreboard.getPlayersTeam("carol"));
    }

    @Test
    void aConcurrentPluginAssignmentIsNeverOverwrittenByTheJoin() throws Exception {
        final PlayerTeam red = this.scoreboard.addPlayerTeam("red");
        final CyclicBarrier start = new CyclicBarrier(2);
        for (int i = 0; i < 2000; i++) {
            final String holder = "p" + i;
            final Thread plugin = new Thread(() -> {
                try {
                    start.await();
                } catch (final Exception e) {
                    throw new IllegalStateException(e);
                }
                this.scoreboard.addPlayerToTeam(holder, red);
            });
            plugin.start();
            final List<Runnable> task = new ArrayList<>();
            PlayerCollisionTeam.join(this.scoreboard, TEAM, holder, task::add);
            start.await();
            task.get(0).run();
            plugin.join();
            // Either order ends in red: the join runs first and the plugin moves the player, or the plugin
            // runs first and the join leaves it alone. Ending in the collision team means the join overwrote it.
            assertSame(red, this.scoreboard.getPlayersTeam(holder), "iteration " + i);
        }
    }
}
