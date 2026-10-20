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
 * OBS-14 engine subcommands: {@link ReloadSubCommand}. Running it reloads {@code config/canvas-*.yml}
 * of the working directory and broadcasts through the server, so only its identity, permission and
 * grammar are tested here; the reload itself is covered through {@code ConfigurationProvider} in
 * {@code ConfigurationUpgradeTest} and needs a booted server to run end to end.
 */
class ReloadSubCommandTest {

    private final SubCommand sub = new ReloadSubCommand();

    @BeforeAll
    static void bootstrapMinecraft() {
        SharedConstants.tryDetectVersion();
        Bootstrap.bootStrap();
    }

    @Test
    void identity() {
        assertEquals("reload", this.sub.getName());
        assertFalse(this.sub.getDescription().isBlank());
        assertFalse(this.sub.isAllowedSelfCommand(), "reload exists only under /canvas, never as a bare /reload");
        assertFalse(this.sub.hasExtraArgs());
        assertEquals("reload", this.sub.getName());
    }

    @Test
    void permissionNodeIsCanvasCommandReload() {
        assertGuardedByCanvasNode(this.sub, "canvas.command.reload");
        final var source = new SubCommandTestSupport.RecordingSource(true);
        assertTrue(this.sub.check("x").test(source.stack));
        assertEquals(java.util.List.of("canvas.command.reload.x"), source.asked);
    }

    @Test
    void grammarIsTheBareLiteralOnly() {
        final CommandDispatcher<CommandSourceStack> dispatcher = dispatcher(this.sub);
        final var source = new SubCommandTestSupport.RecordingSource(true);

        assertTrue(executable(dispatcher, "reload", source.stack));
        assertFalse(executable(dispatcher, "reload now", source.stack), "no arguments");
        assertFalse(executable(dispatcher, "relo", source.stack));
    }
}
