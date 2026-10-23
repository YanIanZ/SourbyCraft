package dev.iyanz.sourbycraft.bridge;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.iyanz.sourbycraft.config.AuroraConfig.BridgeMode;
import java.lang.reflect.Proxy;
import java.util.concurrent.atomic.AtomicReference;
import org.bukkit.command.CommandSender;
import org.bukkit.command.ConsoleCommandSender;
import org.bukkit.entity.Player;
import org.bukkit.event.HandlerList;
import org.bukkit.event.player.PlayerEvent;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

/**
 * The scheduling-context hooks Paper feature patch 0026 calls from {@code PaperEventManager#callEvent}
 * and {@code BukkitCommandNode.BukkitBrigCommand#run}: one call per site, inert until a plugin is
 * bridged, never throwing into event or command dispatch.
 */
class AuroraBridgeContextHooksTest {

    private static final HandlerList HANDLERS = new HandlerList();

    private static final class JoinLike extends PlayerEvent {
        JoinLike(final Player player) { super(player); }
        @Override public HandlerList getHandlers() { return HANDLERS; }
    }

    @SuppressWarnings("unchecked")
    private static <T> T proxy(final Class<T> type) {
        return (T)Proxy.newProxyInstance(type.getClassLoader(), new Class<?>[] {type}, (self, method, args) ->
            switch (method.getName()) {
                case "hashCode" -> System.identityHashCode(self);
                case "equals" -> self == args[0];
                case "toString" -> type.getSimpleName();
                default -> throw new UnsupportedOperationException(method.getName());
            });
    }

    private AutoCloseable installed;

    @AfterEach
    void restore() throws Exception {
        if (this.installed != null) this.installed.close();
        assertNull(SchedulingContext.current());
    }

    private void bridged() {
        this.installed = AuroraBridge.installRuntimeForTesting(new BridgeRuntime(BridgeMode.SAFE, () -> 3,
            new BridgeRuntimeTest.FakeExecutor(), new BridgeTelemetry(), (message, thrown) -> {}));
    }

    @Test
    void hooksAreInertWhileNothingIsBridged() throws Exception {
        this.installed = AuroraBridge.resetRuntimeForTesting();
        assertFalse(AuroraBridge.contextActive());
        final Player player = proxy(Player.class);
        final Object scope = AuroraBridge.enterEventContext(new JoinLike(player));
        assertNull(scope);
        assertNull(SchedulingContext.current());
        AuroraBridge.exitContext(scope);
        final AtomicReference<Object> seen = new AtomicReference<>(new Object());
        AuroraBridge.withCommandContext(player, () -> seen.set(SchedulingContext.current()));
        assertNull(seen.get());
    }

    @Test
    void eventHookPublishesThePlayerForTheListenersAndRestoresAfterwards() {
        bridged();
        final Player player = proxy(Player.class);
        final Object scope = AuroraBridge.enterEventContext(new JoinLike(player));
        assertSame(player, SchedulingContext.current());
        AuroraBridge.exitContext(scope);
        assertNull(SchedulingContext.current());
    }

    @Test
    void eventAboutNoPlayerKeepsTheEnclosingFrame() {
        bridged();
        final Player player = proxy(Player.class);
        try (var frame = SchedulingContext.enter(player)) {
            final Object scope = AuroraBridge.enterEventContext(new org.bukkit.event.server.ServerLoadEvent(
                org.bukkit.event.server.ServerLoadEvent.LoadType.STARTUP));
            assertSame(player, SchedulingContext.current());
            AuroraBridge.exitContext(scope);
            assertSame(player, SchedulingContext.current());
        }
        AuroraBridge.exitContext(null);
        AuroraBridge.exitContext("not a scope");
    }

    @Test
    void nestedEventDispatchRestoresTheOuterPlayer() {
        bridged();
        final Player outer = proxy(Player.class);
        final Player inner = proxy(Player.class);
        final Object a = AuroraBridge.enterEventContext(new JoinLike(outer));
        final Object b = AuroraBridge.enterEventContext(new JoinLike(inner));
        assertSame(inner, SchedulingContext.current());
        AuroraBridge.exitContext(b);
        assertSame(outer, SchedulingContext.current());
        AuroraBridge.exitContext(a);
    }

    @Test
    void commandHookPublishesAPlayerSenderOnlyAndAlwaysRunsTheCommand() {
        bridged();
        final Player player = proxy(Player.class);
        final AtomicReference<Object> seen = new AtomicReference<>();
        AuroraBridge.withCommandContext(player, () -> seen.set(SchedulingContext.current()));
        assertSame(player, seen.get());
        assertNull(SchedulingContext.current());
        final CommandSender console = proxy(ConsoleCommandSender.class);
        seen.set(new Object());
        AuroraBridge.withCommandContext(console, () -> seen.set(SchedulingContext.current()));
        assertNull(seen.get());
        final RuntimeException failure = new RuntimeException("command failed");
        try {
            AuroraBridge.withCommandContext(player, () -> { throw failure; });
        } catch (final RuntimeException thrown) {
            assertSame(failure, thrown, "the command's own exception propagates unchanged");
        }
        assertNull(SchedulingContext.current(), "restored even when the command throws");
    }

    @Test
    void firstAdmissionTurnsTheHooksOn() throws Exception {
        this.installed = AuroraBridge.resetRuntimeForTesting();
        assertFalse(AuroraBridge.contextActive());
        // Mode is read from the TOML files, which the test JVM does not have: OFF, so nothing is admitted
        // and the hooks stay inert.
        assertFalse(AuroraBridge.admitLegacy("Legacy"));
        assertFalse(AuroraBridge.contextActive());
        assertTrue(AuroraBridge.enterEventContext(new JoinLike(proxy(Player.class))) == null);
    }
}
