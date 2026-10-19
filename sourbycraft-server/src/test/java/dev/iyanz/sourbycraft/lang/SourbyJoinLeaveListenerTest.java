package dev.iyanz.sourbycraft.lang;

import java.lang.reflect.Constructor;
import java.lang.reflect.Proxy;
import java.util.List;
import java.util.Map;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.serializer.plain.PlainTextComponentSerializer;
import org.bukkit.entity.Player;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.junit.jupiter.api.Test;

import static dev.iyanz.sourbycraft.lang.SourbyMessagesTest.withConfig;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * OBS-14 / TEST-COVERAGE-GAPS #5: join/leave broadcast override.
 *
 * <p>A broadcast is suppressed either by another plugin (a {@code null} message) or by the operator
 * setting {@code messages.join} / {@code messages.leave} to an empty list (or one with only blank
 * entries). An absent key keeps the built-in variants.</p>
 */
class SourbyJoinLeaveListenerTest {

    private static final PlainTextComponentSerializer PLAIN = PlainTextComponentSerializer.plainText();

    @Test
    void joinMessageIsReplacedWithTheConfiguredVariantAndPlayerName() throws Exception {
        final PlayerJoinEvent event = new PlayerJoinEvent(player("Alex"), Component.text("Alex joined the game"));

        withConfig(Map.of("messages.join", List.of("<green>+ <player></green>")), () -> listener().onJoin(event));

        assertEquals("+ Alex", PLAIN.serialize(event.joinMessage()));
    }

    @Test
    void quitMessageIsReplacedWithTheConfiguredVariantAndPlayerName() throws Exception {
        final PlayerQuitEvent event = new PlayerQuitEvent(player("Alex"), Component.text("Alex left the game"), PlayerQuitEvent.QuitReason.DISCONNECTED);

        withConfig(Map.of("messages.leave", List.of("- <player>")), () -> listener().onQuit(event));

        assertEquals("- Alex", PLAIN.serialize(event.quitMessage()));
    }

    @Test
    void aMessageSuppressedByAnotherPluginStaysSuppressed() throws Exception {
        final PlayerJoinEvent join = new PlayerJoinEvent(player("Alex"), (Component) null);
        final PlayerQuitEvent quit = new PlayerQuitEvent(player("Alex"), (Component) null, PlayerQuitEvent.QuitReason.DISCONNECTED);

        withConfig(Map.of(), () -> {
            listener().onJoin(join);
            listener().onQuit(quit);
        });

        assertNull(join.joinMessage());
        assertNull(quit.quitMessage());
    }

    @Test
    void anExplicitlyEmptyListSwitchesTheBroadcastOff() throws Exception {
        final PlayerJoinEvent join = new PlayerJoinEvent(player("Alex"), Component.text("Alex joined the game"));
        final PlayerQuitEvent quit = new PlayerQuitEvent(player("Alex"), Component.text("Alex left the game"), PlayerQuitEvent.QuitReason.DISCONNECTED);

        withConfig(Map.of("messages.join", List.of(), "messages.leave", List.of()), () -> {
            listener().onJoin(join);
            listener().onQuit(quit);
        });

        assertNull(join.joinMessage());
        assertNull(quit.quitMessage());
    }

    @Test
    void aListWithOnlyBlankEntriesAlsoSwitchesTheBroadcastOff() throws Exception {
        final PlayerJoinEvent join = new PlayerJoinEvent(player("Alex"), Component.text("Alex joined the game"));

        withConfig(Map.of("messages.join", List.of("", "  ")), () -> listener().onJoin(join));

        assertNull(join.joinMessage());
    }

    @Test
    void disablingJoinDoesNotDisableLeaveAndViceVersa() throws Exception {
        final PlayerJoinEvent join = new PlayerJoinEvent(player("Alex"), Component.text("Alex joined the game"));
        final PlayerQuitEvent quit = new PlayerQuitEvent(player("Alex"), Component.text("Alex left the game"), PlayerQuitEvent.QuitReason.DISCONNECTED);

        withConfig(Map.of("messages.join", List.of()), () -> {
            listener().onJoin(join);
            listener().onQuit(quit);
        });

        assertNull(join.joinMessage());
        final String leave = PLAIN.serialize(quit.quitMessage());
        assertTrue(leave.contains("Alex") && !leave.equals("Alex left the game"), leave);
    }

    @Test
    void anAbsentKeyKeepsTheBuiltInDefaults() throws Exception {
        final PlayerJoinEvent join = new PlayerJoinEvent(player("Alex"), Component.text("Alex joined the game"));
        final PlayerQuitEvent quit = new PlayerQuitEvent(player("Alex"), Component.text("Alex left the game"), PlayerQuitEvent.QuitReason.DISCONNECTED);

        withConfig(Map.of(), () -> {
            listener().onJoin(join);
            listener().onQuit(quit);
        });

        final String joined = PLAIN.serialize(join.joinMessage());
        final String left = PLAIN.serialize(quit.quitMessage());
        assertTrue(joined.contains("Alex") && !joined.equals("Alex joined the game"), joined);
        assertTrue(left.contains("Alex") && !left.equals("Alex left the game"), left);
    }

    @Test
    void aWrongTypedValueIsNotAnEmptyListAndKeepsTheDefaults() throws Exception {
        final PlayerJoinEvent join = new PlayerJoinEvent(player("Alex"), Component.text("Alex joined the game"));

        withConfig(Map.of("messages.join", "not a list"), () -> listener().onJoin(join));

        final String joined = PLAIN.serialize(join.joinMessage());
        assertTrue(joined.contains("Alex") && !joined.equals("Alex joined the game"), joined);
    }

    @Test
    void aPlayerNameWithTagsIsBroadcastLiterally() throws Exception {
        final PlayerJoinEvent event = new PlayerJoinEvent(player("<obf>x"), Component.text("joined"));

        withConfig(Map.of("messages.join", List.of("<player> masuk")), () -> listener().onJoin(event));

        assertEquals("<obf>x masuk", PLAIN.serialize(event.joinMessage()));
    }

    private static SourbyJoinLeaveListener listener() throws ReflectiveOperationException {
        final Constructor<SourbyJoinLeaveListener> constructor = SourbyJoinLeaveListener.class.getDeclaredConstructor();
        constructor.setAccessible(true);
        return constructor.newInstance();
    }

    private static Player player(final String name) {
        return (Player) Proxy.newProxyInstance(Player.class.getClassLoader(), new Class<?>[] {Player.class},
            (proxy, method, args) -> switch (method.getName()) {
                case "getName" -> name;
                case "hashCode" -> System.identityHashCode(proxy);
                case "equals" -> proxy == args[0];
                case "toString" -> "Player[" + name + "]";
                default -> throw new AssertionError("unexpected Player call: " + method.getName());
            });
    }
}
