package dev.iyanz.aurora.engine.subcommands;

import com.mojang.brigadier.CommandDispatcher;
import dev.iyanz.aurora.engine.commands.SubCommand;
import net.minecraft.SharedConstants;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.server.Bootstrap;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import static dev.iyanz.aurora.engine.subcommands.SubCommandTestSupport.assertGuardedByCanvasNode;
import static dev.iyanz.aurora.engine.subcommands.SubCommandTestSupport.dispatcher;
import static dev.iyanz.aurora.engine.subcommands.SubCommandTestSupport.executable;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * OBS-14 engine subcommands: {@link RegionDataCommand} is an unfinished placeholder (its
 * {@code construct} still carries the TODO list of planned features). The test pins exactly that
 * state, so the day someone gives it behaviour they have to give it a test too: it has a name and a
 * description, the standard permission, and no executable grammar at all.
 */
class RegionDataCommandTest {

    private final SubCommand sub = new RegionDataCommand();

    @BeforeAll
    static void bootstrapMinecraft() {
        SharedConstants.tryDetectVersion();
        Bootstrap.bootStrap();
    }

    @Test
    void identityAndPermission() {
        assertEquals("regiondata", this.sub.getName());
        assertFalse(this.sub.getDescription().isBlank());
        assertGuardedByCanvasNode(this.sub, "canvas.command.regiondata");
    }

    @Test
    void thereIsNothingToExecuteYet() {
        final CommandDispatcher<CommandSourceStack> dispatcher = dispatcher(this.sub);
        final CommandSourceStack source = new SubCommandTestSupport.RecordingSource(true).stack;

        assertTrue(dispatcher.getRoot().getChild("regiondata").getChildren().isEmpty(), "no sub nodes");
        assertFalse(executable(dispatcher, "regiondata", source));
        assertFalse(executable(dispatcher, "regiondata entities", source));
    }
}
