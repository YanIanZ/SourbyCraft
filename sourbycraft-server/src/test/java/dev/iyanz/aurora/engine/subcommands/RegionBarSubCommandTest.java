package dev.iyanz.aurora.engine.subcommands;

import com.mojang.brigadier.CommandDispatcher;
import dev.iyanz.aurora.engine.commands.SubCommand;
import dev.iyanz.aurora.engine.world.RegionResourceBar;
import io.papermc.paper.threadedregions.EntityScheduler;
import java.lang.reflect.Field;
import java.util.List;
import java.util.function.Consumer;
import net.minecraft.SharedConstants;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.network.chat.Component;
import net.minecraft.server.Bootstrap;
import net.minecraft.server.level.ServerPlayer;
import org.bukkit.craftbukkit.entity.CraftEntity;
import org.bukkit.craftbukkit.entity.CraftPlayer;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import static dev.iyanz.aurora.engine.subcommands.SubCommandTestSupport.assertGuardedByCanvasNode;
import static dev.iyanz.aurora.engine.subcommands.SubCommandTestSupport.dispatcher;
import static dev.iyanz.aurora.engine.subcommands.SubCommandTestSupport.executable;
import static dev.iyanz.aurora.engine.subcommands.SubCommandTestSupport.suggestions;
import static dev.iyanz.aurora.engine.subcommands.SubCommandTestSupport.syntaxError;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * OBS-14 engine subcommands: {@link RegionBarSubCommand} ({@code regionbar <tps_bar|ram_bar>
 * [players [action_bar|boss_bar]]}). Runs without a server: the player is a mock whose entity
 * scheduler runs the task inline (what the real one does when the caller already owns the player's
 * region) and whose two {@code DisplayManager}s are mocks, so the test sees the exact
 * {@code Entry} written back and the exact text sent to the executing source.
 */
class RegionBarSubCommandTest {

    private final SubCommand sub = new RegionBarSubCommand();

    @BeforeAll
    static void bootstrapMinecraft() {
        SharedConstants.tryDetectVersion();
        Bootstrap.bootStrap();
    }

    /** A source whose executing player is {@code player}, recording messages. */
    private static SubCommandTestSupport.RecordingSource sourceOf(final ServerPlayer player) throws Exception {
        final var source = new SubCommandTestSupport.RecordingSource(true);
        when(source.stack.getPlayerOrException()).thenReturn(player);
        return source;
    }

    private static final class Fixture {
        final ServerPlayer player = mock(ServerPlayer.class);
        final RegionResourceBar.DisplayManager tps = mock(RegionResourceBar.DisplayManager.class);
        final RegionResourceBar.DisplayManager ram = mock(RegionResourceBar.DisplayManager.class);

        Fixture(final RegionResourceBar.Entry tpsEntry, final RegionResourceBar.Entry ramEntry) throws Exception {
            setField(ServerPlayer.class, this.player, "canvas$tpsBarDisplay", this.tps);
            setField(ServerPlayer.class, this.player, "canvas$ramBarDisplay", this.ram);
            when(this.tps.serializeDisplay()).thenReturn(tpsEntry);
            when(this.ram.serializeDisplay()).thenReturn(ramEntry);
            when(this.player.getName()).thenReturn(Component.literal("Steve"));

            final CraftPlayer craft = mock(CraftPlayer.class);
            final EntityScheduler scheduler = mock(EntityScheduler.class);
            setField(CraftEntity.class, craft, "taskScheduler", scheduler);
            when(this.player.getBukkitEntity()).thenReturn(craft);
            doAnswer(call -> {
                @SuppressWarnings("unchecked") final Consumer<ServerPlayer> task = call.getArgument(0);
                task.accept(this.player);
                return Boolean.TRUE;
            }).when(scheduler).scheduleOrExecute(any());
        }
    }

    private static void setField(final Class<?> owner, final Object target, final String name, final Object value) throws Exception {
        final Field field = owner.getDeclaredField(name);
        field.setAccessible(true);
        field.set(target, value);
    }

    @Test
    void identityAndPermission() {
        assertEquals("regionbar", this.sub.getName());
        assertFalse(this.sub.getDescription().isBlank());
        assertTrue(this.sub.isAllowedSelfCommand());
        assertGuardedByCanvasNode(this.sub, "canvas.command.regionbar");
    }

    @Test
    void grammarIsTypeThenOptionalPlayersThenOptionalPlacement() {
        final CommandDispatcher<CommandSourceStack> d = dispatcher(this.sub);
        final CommandSourceStack source = new SubCommandTestSupport.RecordingSource(true).stack;

        assertTrue(executable(d, "regionbar tps_bar", source));
        assertTrue(executable(d, "regionbar ram_bar Steve", source));
        assertTrue(executable(d, "regionbar tps_bar Steve boss_bar", source));
        assertTrue(executable(d, "regionbar tps_bar Steve action_bar", source));

        assertFalse(executable(d, "regionbar", source));
        assertFalse(executable(d, "regionbar tps_bar Steve boss_bar extra", source));
    }

    @Test
    void typeAndPlacementAreSuggested() throws Exception {
        final CommandDispatcher<CommandSourceStack> d = dispatcher(this.sub);
        final CommandSourceStack source = new SubCommandTestSupport.RecordingSource(true).stack;

        assertEquals(List.of("ram_bar", "tps_bar"), suggestions(d, "regionbar ", source));
        assertEquals(List.of("action_bar", "boss_bar"), suggestions(d, "regionbar tps_bar Steve ", source));
    }

    @Test
    void anUnknownBarTypeIsRefusedWithAnExplanationBeforeAnythingIsScheduled() throws Exception {
        final Fixture fixture = new Fixture(new RegionResourceBar.Entry(false, RegionResourceBar.Placement.BOSS_BAR),
            new RegionResourceBar.Entry(false, RegionResourceBar.Placement.BOSS_BAR));
        final var source = sourceOf(fixture.player);

        final String message = syntaxError(dispatcher(this.sub), "regionbar nonsense", source.stack);

        assertEquals("Invalid bar type: must be \"tps_bar\" or \"ram_bar\".", message);
        verify(fixture.tps, never()).updateFromEntry(any());
        verify(fixture.ram, never()).updateFromEntry(any());
    }

    @Test
    void theBarTypeIsCaseInsensitive() throws Exception {
        final Fixture fixture = new Fixture(new RegionResourceBar.Entry(false, RegionResourceBar.Placement.BOSS_BAR),
            new RegionResourceBar.Entry(false, RegionResourceBar.Placement.BOSS_BAR));
        final var source = sourceOf(fixture.player);

        assertEquals(1, dispatcher(this.sub).execute("regionbar TPS_BAR", source.stack));

        verify(fixture.tps).updateFromEntry(new RegionResourceBar.Entry(true, RegionResourceBar.Placement.BOSS_BAR));
    }

    @Test
    void togglingAnOffTpsBarTurnsItOnKeepsItsPlacementAndTellsTheSource() throws Exception {
        final Fixture fixture = new Fixture(new RegionResourceBar.Entry(false, RegionResourceBar.Placement.ACTION_BAR),
            new RegionResourceBar.Entry(true, RegionResourceBar.Placement.BOSS_BAR));
        final var source = sourceOf(fixture.player);

        assertEquals(1, dispatcher(this.sub).execute("regionbar tps_bar", source.stack));

        verify(fixture.tps).updateFromEntry(new RegionResourceBar.Entry(true, RegionResourceBar.Placement.ACTION_BAR));
        verify(fixture.ram, never()).updateFromEntry(any());
        assertEquals(List.of("Enabled TPS_BAR for Steve"), source.messages);
    }

    @Test
    void togglingAnOnRamBarTurnsItOff() throws Exception {
        final Fixture fixture = new Fixture(new RegionResourceBar.Entry(false, RegionResourceBar.Placement.BOSS_BAR),
            new RegionResourceBar.Entry(true, RegionResourceBar.Placement.BOSS_BAR));
        final var source = sourceOf(fixture.player);

        assertEquals(1, dispatcher(this.sub).execute("regionbar ram_bar", source.stack));

        verify(fixture.ram).updateFromEntry(new RegionResourceBar.Entry(false, RegionResourceBar.Placement.BOSS_BAR));
        verify(fixture.tps, never()).updateFromEntry(any());
        assertEquals(List.of("Disabled RAM_BAR for Steve"), source.messages);
    }

    @Test
    void toggleNeedsAPlayerSourceAndSchedulesNothingWithout() throws Exception {
        final Fixture fixture = new Fixture(new RegionResourceBar.Entry(false, RegionResourceBar.Placement.BOSS_BAR),
            new RegionResourceBar.Entry(false, RegionResourceBar.Placement.BOSS_BAR));
        final var source = new SubCommandTestSupport.RecordingSource(true);
        when(source.stack.getPlayerOrException()).thenThrow(CommandSourceStack.ERROR_NOT_PLAYER.create());

        final String message = syntaxError(dispatcher(this.sub), "regionbar tps_bar", source.stack);

        assertEquals(CommandSourceStack.ERROR_NOT_PLAYER.create().getMessage(), message);
        verify(fixture.tps, never()).updateFromEntry(any());
    }
}
