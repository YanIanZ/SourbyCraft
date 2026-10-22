package dev.iyanz.sourbycraft.maxplayers;

import java.lang.reflect.Constructor;
import java.net.InetAddress;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.serializer.plain.PlainTextComponentSerializer;
import org.bukkit.Server;
import org.bukkit.entity.Player;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerLoginEvent;
import org.bukkit.plugin.Plugin;
import org.bukkit.plugin.PluginManager;
import org.junit.jupiter.api.Test;

import static dev.iyanz.sourbycraft.maxplayers.TestConfigOverride.proxy;
import static dev.iyanz.sourbycraft.maxplayers.TestConfigOverride.withConfig;
import static dev.iyanz.sourbycraft.maxplayers.TestConfigOverride.withServer;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * OBS-14 / TEST-COVERAGE-GAPS #3: who may join a full server.
 *
 * <p>{@link MaxPlayersBypass} decides admission (P1: a wrong flip lets anyone past the slot cap, a
 * missed one locks staff out). The listener is opt-in because registering a
 * {@code PlayerLoginEvent} listener costs the fast configuration-phase join path.</p>
 */
@SuppressWarnings({"deprecation", "removal"})
class MaxPlayersBypassTest {

    private static final Component VANILLA_FULL = Component.text("The server is full!");

    @Test
    void defaultConfigRegistersNoListenerAndNeverTouchesBukkit() throws Exception {
        // Bukkit.server is null in unit tests: reaching getPluginManager() would throw.
        withConfig(Map.of(), () -> assertFalse(MaxPlayersBypass.register(plugin())));
    }

    @Test
    void explicitFalseOrANonBooleanValueRegistersNoListener() throws Exception {
        withConfig(Map.of(MaxPlayersBypass.ENABLED_KEY, false), () -> assertFalse(MaxPlayersBypass.register(plugin())));
        // A quoted string is not a boolean and falls back to the default.
        withConfig(Map.of(MaxPlayersBypass.ENABLED_KEY, "true"), () -> assertFalse(MaxPlayersBypass.register(plugin())));
    }

    @Test
    void enabledKeyRegistersExactlyOneListenerAgainstThePlugin() throws Exception {
        final List<Object[]> registrations = new ArrayList<>();
        final PluginManager plugins = proxy(PluginManager.class, method -> null, (method, args) -> {
            if (method.getName().equals("registerEvents")) registrations.add(args);
        });
        final Server server = proxy(Server.class,
            method -> method.getName().equals("getPluginManager") ? plugins : null, (method, args) -> {});
        final Plugin plugin = plugin();

        withConfig(Map.of(MaxPlayersBypass.ENABLED_KEY, true),
            () -> withServer(server, () -> assertTrue(MaxPlayersBypass.register(plugin))));

        assertEquals(1, registrations.size());
        assertInstanceOf(MaxPlayersBypass.class, registrations.get(0)[0]);
        assertSame(plugin, registrations.get(0)[1]);
    }

    @Test
    void fullServerAdmitsAPermissionHolder() throws Exception {
        final PlayerLoginEvent event = login(player(true, false), PlayerLoginEvent.Result.KICK_FULL);

        listener().onLogin(event);

        assertEquals(PlayerLoginEvent.Result.ALLOWED, event.getResult());
    }

    @Test
    void fullServerAdmitsAnOperatorWithoutThePermission() throws Exception {
        final PlayerLoginEvent event = login(player(false, true), PlayerLoginEvent.Result.KICK_FULL);

        listener().onLogin(event);

        assertEquals(PlayerLoginEvent.Result.ALLOWED, event.getResult());
    }

    @Test
    void fullServerKeepsTheKickForOthersAndUsesTheConfiguredMessage() throws Exception {
        final PlayerLoginEvent event = login(player(false, false), PlayerLoginEvent.Result.KICK_FULL);

        withConfig(Map.of("messages.server-full", List.of("<red>Penuh, coba lagi</red>")),
            () -> listener().onLogin(event));

        assertEquals(PlayerLoginEvent.Result.KICK_FULL, event.getResult());
        assertEquals("Penuh, coba lagi", PlainTextComponentSerializer.plainText().serialize(event.kickMessage()));
    }

    @Test
    void fullServerWithoutConfiguredMessagesStillReplacesTheVanillaLine() throws Exception {
        final PlayerLoginEvent event = login(player(false, false), PlayerLoginEvent.Result.KICK_FULL);

        withConfig(Map.of(), () -> listener().onLogin(event));

        assertEquals(PlayerLoginEvent.Result.KICK_FULL, event.getResult());
        final String message = PlainTextComponentSerializer.plainText().serialize(event.kickMessage());
        assertFalse(message.isBlank());
        assertFalse(message.equals(PlainTextComponentSerializer.plainText().serialize(VANILLA_FULL)));
    }

    @Test
    void otherKickReasonsAreNeverTouchedEvenForBypassHolders() throws Exception {
        for (final PlayerLoginEvent.Result result : PlayerLoginEvent.Result.values()) {
            if (result == PlayerLoginEvent.Result.KICK_FULL) continue;
            final PlayerLoginEvent event = login(player(true, true), result);

            listener().onLogin(event);

            assertEquals(result, event.getResult(), result.name());
            assertSame(VANILLA_FULL, event.kickMessage(), result.name());
        }
    }

    // ------------------------------------------------------------------------------------ helpers

    private static MaxPlayersBypass listener() throws ReflectiveOperationException {
        final Constructor<MaxPlayersBypass> constructor = MaxPlayersBypass.class.getDeclaredConstructor();
        constructor.setAccessible(true);
        final MaxPlayersBypass listener = constructor.newInstance();
        assertInstanceOf(Listener.class, listener);
        return listener;
    }

    private static PlayerLoginEvent login(final Player player, final PlayerLoginEvent.Result result) {
        final InetAddress address = InetAddress.getLoopbackAddress();
        return new PlayerLoginEvent(player, "localhost", address, result, VANILLA_FULL, address);
    }

    private static Player player(final boolean bypassPermission, final boolean op) {
        return proxy(Player.class, method -> switch (method.getName()) {
            case "hasPermission" -> bypassPermission;
            case "isOp" -> op;
            case "getName" -> "Tester";
            default -> null;
        }, (method, args) -> {
            if (method.getName().equals("hasPermission")) {
                assertEquals(MaxPlayersBypass.BYPASS_PERMISSION, args[0]);
            }
        });
    }

    private static Plugin plugin() {
        return proxy(Plugin.class, method -> null, (method, args) -> {});
    }
}
