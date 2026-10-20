package dev.iyanz.aurora.engine.subcommands;

import com.mojang.brigadier.CommandDispatcher;
import dev.iyanz.aurora.engine.commands.SubCommand;
import java.util.List;
import net.minecraft.SharedConstants;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.server.Bootstrap;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import static dev.iyanz.aurora.engine.subcommands.SubCommandTestSupport.assertGuardedByCanvasNode;
import static dev.iyanz.aurora.engine.subcommands.SubCommandTestSupport.dispatcher;
import static dev.iyanz.aurora.engine.subcommands.SubCommandTestSupport.executable;
import static dev.iyanz.aurora.engine.subcommands.SubCommandTestSupport.suggestions;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * OBS-14 engine subcommands: {@link RegionTickSubCommand} ({@code tick rate|sprint|walk|pause|play}).
 * Executing any branch posts to the live tick scheduler ({@code TickRegionScheduler.setTickRate},
 * region handles), which is process-wide server state, so it is not executed here; the tests cover
 * identity, the permission node, the exact argument grammar with its limits (rate at least 0, sprint
 * 0..100000 ticks) and the handle suggestions that are offered without a server.
 */
class RegionTickSubCommandTest {

    private final SubCommand sub = new RegionTickSubCommand();
    private final CommandSourceStack source = new SubCommandTestSupport.RecordingSource(true).stack;

    @BeforeAll
    static void bootstrapMinecraft() {
        SharedConstants.tryDetectVersion();
        Bootstrap.bootStrap();
    }

    @Test
    void identityAndPermission() {
        assertEquals("tick", this.sub.getName());
        assertFalse(this.sub.getDescription().isBlank());
        assertTrue(this.sub.isAllowedSelfCommand());
        assertGuardedByCanvasNode(this.sub, "canvas.command.tick");
    }

    @Test
    void rateTakesOneNonNegativeFloat() {
        final CommandDispatcher<CommandSourceStack> d = dispatcher(this.sub);
        assertTrue(executable(d, "tick rate 20", this.source));
        assertTrue(executable(d, "tick rate 0", this.source));
        assertTrue(executable(d, "tick rate 12.5", this.source));
        assertFalse(executable(d, "tick rate", this.source));
        assertFalse(executable(d, "tick rate -1", this.source));
        assertFalse(executable(d, "tick rate fast", this.source));
        assertFalse(executable(d, "tick rate 20 extra", this.source));
    }

    @Test
    void sprintNeedsTicksWithinTheCapAndAHandle() {
        final CommandDispatcher<CommandSourceStack> d = dispatcher(this.sub);
        assertTrue(executable(d, "tick sprint 100 global", this.source));
        assertTrue(executable(d, "tick sprint 0 server", this.source));
        assertTrue(executable(d, "tick sprint 100000 global", this.source), "the documented 100k cap is inclusive");
        assertTrue(executable(d, "tick sprint 5 10 -20", this.source), "the handle is the rest of the line (chunk x z)");

        assertFalse(executable(d, "tick sprint 100001 global", this.source), "above the cap");
        assertFalse(executable(d, "tick sprint -1 global", this.source));
        assertFalse(executable(d, "tick sprint 100", this.source), "a handle is required");
        assertFalse(executable(d, "tick sprint global", this.source), "ticks come first");
        assertFalse(executable(d, "tick sprint 1.5 global", this.source));
    }

    @Test
    void walkPausePlayEachTakeOnlyAHandle() {
        final CommandDispatcher<CommandSourceStack> d = dispatcher(this.sub);
        for (final String verb : List.of("walk", "pause", "play")) {
            assertTrue(executable(d, "tick " + verb + " global", this.source), verb);
            assertTrue(executable(d, "tick " + verb + " server", this.source), verb);
            assertTrue(executable(d, "tick " + verb + " 1 2", this.source), verb);
            assertFalse(executable(d, "tick " + verb, this.source), verb + " without handle");
        }
    }

    @Test
    void theRootAndUnknownVerbsDoNothing() {
        final CommandDispatcher<CommandSourceStack> d = dispatcher(this.sub);
        assertFalse(executable(d, "tick", this.source));
        assertFalse(executable(d, "tick freeze global", this.source));
    }

    @Test
    void verbsAreSuggestedAndHandlesOfferGlobalAndServer() throws Exception {
        final CommandDispatcher<CommandSourceStack> d = dispatcher(this.sub);

        assertEquals(List.of("pause", "play", "rate", "sprint", "walk"), suggestions(d, "tick ", this.source));

        org.mockito.Mockito.when(this.source.getRelevantCoordinates()).thenReturn(List.of());
        final List<String> handles = suggestions(d, "tick walk ", this.source);
        assertTrue(handles.containsAll(List.of("global", "server")), handles.toString());
        assertEquals(List.of("global"), suggestions(d, "tick walk gl", this.source));
    }
}
