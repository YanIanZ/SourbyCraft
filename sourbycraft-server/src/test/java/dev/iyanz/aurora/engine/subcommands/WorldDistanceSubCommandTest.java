package dev.iyanz.aurora.engine.subcommands;

import com.mojang.brigadier.CommandDispatcher;
import com.mojang.brigadier.exceptions.CommandSyntaxException;
import dev.iyanz.aurora.engine.commands.SubCommand;
import dev.iyanz.aurora.engine.world.PerWorldDistanceConfig;
import io.papermc.paper.world.saveddata.PaperLevelOverrides;
import java.lang.reflect.Field;
import java.util.List;
import java.util.Optional;
import net.minecraft.SharedConstants;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.resources.ResourceKey;
import net.minecraft.core.registries.Registries;
import net.minecraft.resources.Identifier;
import net.minecraft.server.Bootstrap;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.Level;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import static dev.iyanz.aurora.engine.subcommands.SubCommandTestSupport.dispatcher;
import static dev.iyanz.aurora.engine.subcommands.SubCommandTestSupport.executable;
import static dev.iyanz.aurora.engine.subcommands.SubCommandTestSupport.suggestions;
import static dev.iyanz.aurora.engine.subcommands.SubCommandTestSupport.syntaxError;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * OBS-14 engine subcommands: {@link WorldDistanceSubCommand} ({@code worlddistance <view|simulation>
 * <dimension> <get|set|unset>}). The only sub command that sets its own requirement (admin level plus
 * {@code canvas.command.worlddistance}); {@code get} and the rejected {@code set} values run against a
 * mocked level and source. {@code set}/{@code unset} that succeed also reach into the level's chunk map
 * and broadcast to players, so they are not executed.
 */
class WorldDistanceSubCommandTest {

    private final SubCommand sub = new WorldDistanceSubCommand();

    @BeforeAll
    static void bootstrapMinecraft() {
        SharedConstants.tryDetectVersion();
        Bootstrap.bootStrap();
    }

    private static void setField(final Class<?> owner, final Object target, final String name, final Object value) throws Exception {
        final Field field = owner.getDeclaredField(name);
        field.setAccessible(true);
        field.set(target, value);
    }

    /** A source whose server knows {@code overworld} as a level with the given distance overrides. */
    private static SubCommandTestSupport.RecordingSource sourceWithOverworld(final int view, final int simulation) throws Exception {
        final var source = new SubCommandTestSupport.RecordingSource(true);
        final MinecraftServer server = mock(MinecraftServer.class);
        final ServerLevel level = mock(ServerLevel.class);
        final PaperLevelOverrides overrides = mock(PaperLevelOverrides.class);
        final PerWorldDistanceConfig distances = new PerWorldDistanceConfig(
            Optional.of(view), Optional.of(simulation));
        setField(PaperLevelOverrides.class, overrides, "canvas$distanceConfig", distances);
        setField(ServerLevel.class, level, "serverLevelData", overrides);
        when(level.dimension()).thenReturn(Level.OVERWORLD);
        when(server.getLevel(ResourceKey.create(Registries.DIMENSION, Identifier.parse("minecraft:overworld")))).thenReturn(level);
        when(source.stack.getServer()).thenReturn(server);
        return source;
    }

    @Test
    void identity() {
        assertEquals("worlddistance", this.sub.getName());
        assertFalse(this.sub.getDescription().isBlank());
        assertTrue(this.sub.isAllowedSelfCommand());
    }

    @Test
    void permissionIsTheAdminLevelPlusItsOwnNodeAndRejectedSendersCannotParse() {
        final CommandDispatcher<CommandSourceStack> d = dispatcher(this.sub);
        final var denied = new SubCommandTestSupport.RecordingSource(false);
        final var granted = new SubCommandTestSupport.RecordingSource(true);

        assertFalse(d.getRoot().getChild("worlddistance").canUse(denied.stack));
        assertTrue(d.getRoot().getChild("worlddistance").canUse(granted.stack));
        assertEquals(List.of("canvas.command.worlddistance"), denied.asked);
        assertFalse(executable(d, "worlddistance view minecraft:overworld get", denied.stack));
        assertTrue(executable(d, "worlddistance view minecraft:overworld get", granted.stack));
    }

    @Test
    void grammarIsTypeDimensionThenGetSetOrUnset() {
        final CommandDispatcher<CommandSourceStack> d = dispatcher(this.sub);
        final CommandSourceStack source = new SubCommandTestSupport.RecordingSource(true).stack;

        assertTrue(executable(d, "worlddistance view minecraft:overworld get", source));
        assertTrue(executable(d, "worlddistance simulation minecraft:the_nether get", source));
        assertTrue(executable(d, "worlddistance s minecraft:overworld unset", source));
        assertTrue(executable(d, "worlddistance view minecraft:overworld set 12", source));
        assertTrue(executable(d, "worlddistance view minecraft:overworld set -3", source), "range is checked at execution, not by the parser");

        assertFalse(executable(d, "worlddistance", source));
        assertFalse(executable(d, "worlddistance view", source));
        assertFalse(executable(d, "worlddistance view minecraft:overworld", source));
        assertFalse(executable(d, "worlddistance view minecraft:overworld set", source));
        assertFalse(executable(d, "worlddistance view minecraft:overworld set twelve", source));
        assertFalse(executable(d, "worlddistance view minecraft:overworld frob", source));
    }

    @Test
    void typeAndActionAreSuggested() throws Exception {
        final CommandDispatcher<CommandSourceStack> d = dispatcher(this.sub);
        final CommandSourceStack source = new SubCommandTestSupport.RecordingSource(true).stack;

        assertEquals(List.of("simulation", "view"), suggestions(d, "worlddistance ", source));
        assertEquals(List.of("get", "set", "unset"), suggestions(d, "worlddistance view minecraft:overworld ", source));
    }

    @Test
    void typeAliasesResolveAndAnythingElseIsRefused() throws CommandSyntaxException {
        assertEquals(WorldDistanceSubCommand.Type.VIEW, WorldDistanceSubCommand.Type.from("view"));
        assertEquals(WorldDistanceSubCommand.Type.VIEW, WorldDistanceSubCommand.Type.from("V"));
        assertEquals(WorldDistanceSubCommand.Type.SIMULATION, WorldDistanceSubCommand.Type.from("simulation"));
        assertEquals(WorldDistanceSubCommand.Type.SIMULATION, WorldDistanceSubCommand.Type.from("SIM"));
        assertEquals(WorldDistanceSubCommand.Type.SIMULATION, WorldDistanceSubCommand.Type.from("s"));

        final CommandSyntaxException refused = assertThrows(CommandSyntaxException.class, () -> WorldDistanceSubCommand.Type.from("render"));
        assertEquals("Illegal type argument. Must be [\"view\", \"simulation\", \"v\", \"s\", or \"sim\"]", refused.getMessage());
    }

    @Test
    void getReportsTheConfiguredDistanceAndReturnsIt() throws Exception {
        final var source = sourceWithOverworld(12, 7);
        final CommandDispatcher<CommandSourceStack> d = dispatcher(this.sub);

        assertEquals(12, d.execute("worlddistance view minecraft:overworld get", source.stack));
        assertEquals(7, d.execute("worlddistance sim minecraft:overworld get", source.stack));

        assertEquals(List.of(
            "View distance of level \"overworld\" is 12",
            "Simulation distance of level \"overworld\" is 7"
        ), source.messages);
    }

    @Test
    void anUnknownDimensionIsReportedNotIgnored() throws Exception {
        final var source = sourceWithOverworld(12, 7);

        final String message = syntaxError(dispatcher(this.sub), "worlddistance view minecraft:nowhere get", source.stack);

        assertTrue(message.contains("minecraft:nowhere"), message);
        assertTrue(source.messages.isEmpty());
    }

    @Test
    void aZeroOrNegativeDistanceIsRejectedBeforeAnythingIsChanged() throws Exception {
        final var source = sourceWithOverworld(12, 7);
        final CommandDispatcher<CommandSourceStack> d = dispatcher(this.sub);

        assertEquals("New value must be above 0", syntaxError(d, "worlddistance view minecraft:overworld set 0", source.stack));
        assertEquals("New value must be above 0", syntaxError(d, "worlddistance simulation minecraft:overworld set -5", source.stack));

        assertEquals(12, d.execute("worlddistance view minecraft:overworld get", source.stack), "the stored value is untouched");
    }

    @Test
    void anIllegalTypeWordIsRefusedAtExecution() throws Exception {
        final var source = sourceWithOverworld(12, 7);

        final String message = syntaxError(dispatcher(this.sub), "worlddistance render minecraft:overworld get", source.stack);

        assertTrue(message.startsWith("Illegal type argument"), message);
    }

}
