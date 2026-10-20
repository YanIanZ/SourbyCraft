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
 * OBS-14 engine subcommands: {@link SetMaxPlayersSubCommand}. Executing it schedules onto the
 * {@code RegionizedServer} and rewrites {@code server.properties}, which needs a booted server, so
 * only identity, permission and the argument grammar (count at least 0, optional persist flag) run
 * here.
 */
class SetMaxPlayersSubCommandTest {

    private final SubCommand sub = new SetMaxPlayersSubCommand();

    @BeforeAll
    static void bootstrapMinecraft() {
        SharedConstants.tryDetectVersion();
        Bootstrap.bootStrap();
    }

    @Test
    void identityAndPermission() {
        assertEquals("setmaxplayers", this.sub.getName());
        assertFalse(this.sub.getDescription().isBlank());
        assertTrue(this.sub.isAllowedSelfCommand());
        assertGuardedByCanvasNode(this.sub, "canvas.command.setmaxplayers");
    }

    @Test
    void aCountWithAnOptionalPersistFlagIsTheOnlyAcceptedShape() {
        final CommandDispatcher<CommandSourceStack> dispatcher = dispatcher(this.sub);
        final CommandSourceStack source = new SubCommandTestSupport.RecordingSource(true).stack;

        assertTrue(executable(dispatcher, "setmaxplayers 20", source));
        assertTrue(executable(dispatcher, "setmaxplayers 0", source), "zero is allowed");
        assertTrue(executable(dispatcher, "setmaxplayers 20 true", source));
        assertTrue(executable(dispatcher, "setmaxplayers 20 false", source));

        assertFalse(executable(dispatcher, "setmaxplayers", source), "count is required");
        assertFalse(executable(dispatcher, "setmaxplayers -1", source), "negative counts are rejected by the parser");
        assertFalse(executable(dispatcher, "setmaxplayers ten", source));
        assertFalse(executable(dispatcher, "setmaxplayers 2.5", source));
        assertFalse(executable(dispatcher, "setmaxplayers 20 maybe", source), "persist is a boolean");
        assertFalse(executable(dispatcher, "setmaxplayers 20 true extra", source));
        assertFalse(executable(dispatcher, "setmaxplayers 99999999999", source), "beyond int");
    }

    @Test
    void anUnpermittedSourceCannotEvenParseTheCommand() {
        final CommandDispatcher<CommandSourceStack> dispatcher = dispatcher(this.sub);
        final CommandSourceStack denied = new SubCommandTestSupport.RecordingSource(false).stack;

        assertFalse(executable(dispatcher, "setmaxplayers 20", denied));
    }
}
