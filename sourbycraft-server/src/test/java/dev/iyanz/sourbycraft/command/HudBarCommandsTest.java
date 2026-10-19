package dev.iyanz.sourbycraft.command;

import static dev.iyanz.sourbycraft.command.CommandTestSupport.*;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import dev.iyanz.sourbycraft.api.metrics.SourbyMetrics;
import dev.iyanz.sourbycraft.bootstrap.MinecraftInternalPlugin;
import io.papermc.paper.threadedregions.scheduler.EntityScheduler;
import io.papermc.paper.threadedregions.scheduler.ScheduledTask;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.lang.reflect.Proxy;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Consumer;
import net.kyori.adventure.bossbar.BossBar;
import org.bukkit.Bukkit;
import org.bukkit.NamespacedKey;
import org.bukkit.Server;
import org.bukkit.command.BlockCommandSender;
import org.bukkit.command.CommandSender;
import org.bukkit.command.ConsoleCommandSender;
import org.bukkit.entity.Player;
import org.bukkit.persistence.PersistentDataContainer;
import org.bukkit.persistence.PersistentDataType;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * {@code /tpsbar}, {@code /rambar} and {@code /perfbar}: permission first, players only, strict
 * argument parsing, and the effect on {@code HudBars} (shown/hidden on the player's own scheduler,
 * preference persisted on the player).
 *
 * <p>The commands live in {@code command} and {@code HudBars.installForTesting} is package-private
 * in {@code hud}, so the seam is reached by reflection: the updater scheduler is replaced by a
 * recorder and no server is needed. {@code HudBars} keeps process-wide static viewer sets; every
 * test uses fresh player ids and {@code HudBars.close()} in teardown.</p>
 */
class HudBarCommandsTest {

    private AutoCloseable restore;
    private final AtomicInteger scheduledUpdaters = new AtomicInteger();

    /** MinecraftInternalPlugin.INSTANCE's static initialiser needs a Server that can answer getLogger once. */
    @BeforeAll
    static void initialiseThePluginOwner() throws Exception {
        final Server stub = (Server) Proxy.newProxyInstance(Server.class.getClassLoader(), new Class<?>[] {Server.class},
            (proxy, method, args) -> switch (method.getName()) {
                case "getLogger" -> java.util.logging.Logger.getLogger("HudBarCommandsTest");
                case "hashCode" -> System.identityHashCode(proxy);
                case "equals" -> proxy == args[0];
                default -> method.getReturnType().isPrimitive() ? (method.getReturnType() == boolean.class ? (Object) false
                    : method.getReturnType() == int.class ? (Object) 0 : method.getReturnType() == long.class ? (Object) 0L
                    : method.getReturnType() == double.class ? (Object) 0.0 : method.getReturnType() == float.class ? (Object) 0.0F
                    : (Object) (byte) 0) : null;
            });
        final Field field = Bukkit.class.getDeclaredField("server");
        field.setAccessible(true);
        final Object previous = field.get(null);
        field.set(null, stub);
        try {
            assertNotNull(MinecraftInternalPlugin.INSTANCE);
        } finally {
            field.set(null, previous);
        }
    }

    @BeforeEach
    void installRecordingScheduler() throws Exception {
        final Class<?> hudBars = Class.forName("dev.iyanz.sourbycraft.hud.HudBars");
        final Class<?> schedulerType = Class.forName("dev.iyanz.sourbycraft.hud.HudBars$UpdaterScheduler");
        final Method install = hudBars.getDeclaredMethod("installForTesting", schedulerType, SourbyMetrics.class);
        install.setAccessible(true);
        final Object scheduler = Proxy.newProxyInstance(schedulerType.getClassLoader(), new Class<?>[] {schedulerType},
            (proxy, method, args) -> {
                if (method.getName().equals("schedule")) {
                    scheduledUpdaters.incrementAndGet();
                    return mock(ScheduledTask.class);
                }
                return method.getName().equals("hashCode") ? (Object) System.identityHashCode(proxy)
                    : method.getName().equals("equals") ? (Object) (proxy == args[0]) : (Object) "scheduler";
            });
        dev.iyanz.sourbycraft.hud.HudBars.close();
        this.restore = (AutoCloseable) install.invoke(null, scheduler,
            (SourbyMetrics) () -> { throw new AssertionError("the updater task must not run in this test"); });
    }

    @AfterEach
    void uninstall() throws Exception {
        dev.iyanz.sourbycraft.hud.HudBars.close();
        this.restore.close();
    }

    /** A player whose entity scheduler queues tasks until {@link #flush}, and whose PDC is a mock. */
    private static final class Rig {
        final Player player;
        final PersistentDataContainer pdc = mock(PersistentDataContainer.class);
        final List<Consumer<ScheduledTask>> queue = new ArrayList<>();
        final ReplyProbe probe;

        @SuppressWarnings("unchecked")
        Rig(final ReplyProbe probe, final boolean permitted) {
            this.probe = probe;
            this.player = probe.sender(Player.class, permitted);
            when(this.player.getUniqueId()).thenReturn(UUID.randomUUID());
            when(this.player.getPersistentDataContainer()).thenReturn(this.pdc);
            final EntityScheduler scheduler = mock(EntityScheduler.class);
            when(scheduler.run(any(), any(Consumer.class), any())).thenAnswer(invocation -> {
                this.queue.add(invocation.getArgument(1));
                return null;
            });
            when(this.player.getScheduler()).thenReturn(scheduler);
        }

        void flush() {
            final List<Consumer<ScheduledTask>> now = new ArrayList<>(this.queue);
            this.queue.clear();
            now.forEach(task -> task.accept(null));
        }
    }

    // ------------------------------------------------------------------------------ permission

    @Test
    void deniedSendersNeverReachTheHud() throws Exception {
        final ReplyProbe probe = new ReplyProbe();
        final List<org.bukkit.command.Command> commands = List.of(
            quietDenial(new HudBarCommand("tpsbar", true)), quietDenial(new HudBarCommand("rambar", false)),
            quietDenial(new PerfBarCommand()));
        for (final org.bukkit.command.Command command : commands) {
            final Rig rig = new Rig(probe, false);
            assertTrue(command.execute(rig.player, command.getName(), new String[] {"on"}));
            rig.flush();
            verify(rig.player, never()).getUniqueId();
            verify(rig.player, never()).showBossBar(any(BossBar.class));
            verifyNoInteractions(rig.pdc);
            assertTrue(rig.queue.isEmpty());
            assertEquals("no permission", probe.next().text());
        }
        assertEquals(0, scheduledUpdaters.get(), "the updater was never started");
        assertEquals("sourbycraft.command.tpsbar", new HudBarCommand("tpsbar", true).getPermission());
        assertEquals("sourbycraft.command.rambar", new HudBarCommand("rambar", false).getPermission());
        assertEquals("sourbycraft.command.perfbar", new PerfBarCommand().getPermission());
    }

    // ------------------------------------------------------------------------ non-player senders

    @Test
    void consoleAndCommandBlocksAreToldTheBarIsAPlayerHud() throws Exception {
        for (final Class<? extends CommandSender> type : List.of(ConsoleCommandSender.class, BlockCommandSender.class)) {
            final ReplyProbe probe = new ReplyProbe();
            final CommandSender sender = probe.captureStrings(probe.sender(type, true));

            assertTrue(new HudBarCommand("tpsbar", true).execute(sender, "tpsbar", new String[] {"on"}));
            assertEquals("Players only — the bar is a client HUD.", probe.next().text());

            assertTrue(new PerfBarCommand().execute(sender, "perfbar", new String[0]));
            assertEquals("Players only: /perfbar is a client HUD.", probe.next().text());
            assertTrue(probe.received().isEmpty());
        }
        assertEquals(0, scheduledUpdaters.get());
    }

    // ------------------------------------------------------------------------------- arguments

    @Test
    void badArgumentsPrintUsageAndChangeNothing() throws Exception {
        final String[][] bad = {{"maybe"}, {"1"}, {"toggle"}, {"on", "off"}, {"on", "extra"}, {""}};
        for (final String[] args : bad) {
            final ReplyProbe probe = new ReplyProbe();
            final Rig rig = new Rig(probe, true);
            probe.captureStrings(rig.player);

            new HudBarCommand("rambar", false).execute(rig.player, "rambar", args);
            assertEquals("/rambar [on|off]", probe.next().text(), java.util.Arrays.toString(args));

            new PerfBarCommand().execute(rig.player, "perfbar", args);
            assertEquals("/perfbar [on|off]", probe.next().text(), java.util.Arrays.toString(args));

            rig.flush();
            verify(rig.player, never()).showBossBar(any(BossBar.class));
            verify(rig.player, never()).hideBossBar(any(BossBar.class));
            verifyNoInteractions(rig.pdc);
        }
        assertEquals(0, scheduledUpdaters.get());
    }

    // ----------------------------------------------------------------------------- /tpsbar /rambar

    @Test
    void tpsbarTogglesShownThenHiddenOnThePlayersOwnScheduler() throws Exception {
        final ReplyProbe probe = new ReplyProbe();
        final Rig rig = new Rig(probe, true);
        final HudBarCommand command = new HudBarCommand("tpsbar", true);

        assertTrue(command.execute(rig.player, "tpsbar", new String[0]));
        assertEquals("TPS bar shown", probe.next().text());
        verify(rig.player, never()).showBossBar(any(BossBar.class));      // only after the hop
        rig.flush();
        verify(rig.player, times(1)).showBossBar(any(BossBar.class));

        assertTrue(command.execute(rig.player, "tpsbar", new String[0]));
        assertEquals("TPS bar hidden", probe.next().text());
        rig.flush();
        verify(rig.player, times(1)).hideBossBar(any(BossBar.class));
        assertEquals(1, scheduledUpdaters.get(), "one updater for the whole HUD");
    }

    @Test
    void explicitOnAndOffAreIdempotentCaseInsensitiveAndPersistTheChoice() throws Exception {
        final ReplyProbe probe = new ReplyProbe();
        final Rig rig = new Rig(probe, true);
        final HudBarCommand ram = new HudBarCommand("rambar", false);

        ram.execute(rig.player, "rambar", new String[] {"ON"});
        ram.execute(rig.player, "rambar", new String[] {"on"});
        assertEquals("RAM bar shown", probe.next().text());
        assertEquals("RAM bar shown", probe.next().text());
        // Showing opts back in: the opt-out flag is removed, never set.
        verify(rig.pdc, times(2)).remove(any(NamespacedKey.class));
        verify(rig.pdc, never()).set(any(NamespacedKey.class), eq(PersistentDataType.BYTE), any(Byte.class));

        ram.execute(rig.player, "rambar", new String[] {"Off"});
        assertEquals("RAM bar hidden", probe.next().text());
        verify(rig.pdc).set(any(NamespacedKey.class), eq(PersistentDataType.BYTE), eq((byte) 1));
        rig.flush();
        verify(rig.player, times(2)).showBossBar(any(BossBar.class));
        verify(rig.player, times(1)).hideBossBar(any(BossBar.class));
    }

    // ------------------------------------------------------------------------------- /perfbar

    @Test
    void perfbarTogglesAndPersistsThePreferenceOnThePlayer() throws Exception {
        final ReplyProbe probe = new ReplyProbe();
        final Rig rig = new Rig(probe, true);
        probe.captureStrings(rig.player);
        final PerfBarCommand command = new PerfBarCommand();

        assertTrue(command.execute(rig.player, "perfbar", new String[0]));
        assertEquals("SourbyCraft performance bar shown", probe.next().text());
        verify(rig.pdc).set(any(NamespacedKey.class), eq(PersistentDataType.BYTE), eq((byte) 1));
        rig.flush();
        verify(rig.player, times(1)).showBossBar(any(BossBar.class));

        assertTrue(command.execute(rig.player, "perfbar", new String[0]));
        assertEquals("SourbyCraft performance bar hidden", probe.next().text());
        verify(rig.pdc).set(any(NamespacedKey.class), eq(PersistentDataType.BYTE), eq((byte) 0));
        rig.flush();
        verify(rig.player, times(1)).hideBossBar(any(BossBar.class));
    }

    @Test
    void perfbarExplicitOnAndOffAreIdempotent() throws Exception {
        final ReplyProbe probe = new ReplyProbe();
        final Rig rig = new Rig(probe, true);
        probe.captureStrings(rig.player);
        final PerfBarCommand command = new PerfBarCommand();

        command.execute(rig.player, "perfbar", new String[] {"on"});
        command.execute(rig.player, "perfbar", new String[] {"ON"});
        assertEquals("SourbyCraft performance bar shown", probe.next().text());
        assertEquals("SourbyCraft performance bar shown", probe.next().text());
        command.execute(rig.player, "perfbar", new String[] {"off"});
        command.execute(rig.player, "perfbar", new String[] {"OFF"});
        assertEquals("SourbyCraft performance bar hidden", probe.next().text());
        assertEquals("SourbyCraft performance bar hidden", probe.next().text());
        assertEquals(1, scheduledUpdaters.get());
    }
}
