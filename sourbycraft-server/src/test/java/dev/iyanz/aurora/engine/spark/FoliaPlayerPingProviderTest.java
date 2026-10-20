package dev.iyanz.aurora.engine.spark;

import dev.iyanz.aurora.engine.spark.provider.FoliaPlayerPingProvider;
import java.util.List;
import java.util.Map;
import org.bukkit.Server;
import org.bukkit.entity.Player;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/** OBS-14 engine spark: {@link FoliaPlayerPingProvider} reports each online player's ping by name. */
class FoliaPlayerPingProviderTest {

    private static Player player(final String name, final int ping) {
        final Player player = mock(Player.class);
        when(player.getName()).thenReturn(name);
        when(player.getPing()).thenReturn(ping);
        return player;
    }

    private static Server serverWith(final Player... players) {
        final Server server = mock(Server.class);
        when(server.getOnlinePlayers()).thenAnswer(call -> List.of(players));
        return server;
    }

    @Test
    void emptyServerGivesAnEmptyMap() {
        assertTrue(new FoliaPlayerPingProvider(serverWith()).poll().isEmpty());
    }

    @Test
    void reportsNameToPingAndKeepsZeroAndLargeValues() {
        final Map<String, Integer> polled = new FoliaPlayerPingProvider(serverWith(player("Alex", 23), player("Steve", 0), player("Far", 9_999))).poll();
        assertEquals(Map.of("Alex", 23, "Steve", 0, "Far", 9_999), polled);
    }

    @Test
    void theResultIsAnImmutableSnapshot() {
        final FoliaPlayerPingProvider provider = new FoliaPlayerPingProvider(serverWith(player("Alex", 23)));
        final Map<String, Integer> polled = provider.poll();
        assertThrows(UnsupportedOperationException.class, () -> polled.put("x", 1));
    }

    @Test
    void eachPollReadsTheCurrentPing() {
        final Player alex = player("Alex", 10);
        final FoliaPlayerPingProvider provider = new FoliaPlayerPingProvider(serverWith(alex));
        assertEquals(Map.of("Alex", 10), provider.poll());
        when(alex.getPing()).thenReturn(80);
        assertEquals(Map.of("Alex", 80), provider.poll());
    }
}
