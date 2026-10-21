package dev.iyanz.sourbycraft.guards;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.lang.ref.WeakReference;
import java.util.ArrayList;
import java.util.List;
import org.bukkit.event.HandlerList;
import org.bukkit.plugin.Plugin;
import org.bukkit.plugin.RegisteredListener;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * Review finding 8: the notice ran on every chat message and walked every listener (building a
 * string per listener) each time. It now walks only when the handler list's listener array changed
 * (a plugin registered or unregistered) and still reports a plugin that registers later.
 */
class LegacyListenerNoticeTest {

    private final List<String> log = new ArrayList<>();

    @BeforeEach
    @AfterEach
    void reset() {
        LegacyListenerNotice.resetForTests();
    }

    private static RegisteredListener listenerOf(final String pluginName) {
        final Plugin plugin = mock(Plugin.class);
        when(plugin.getName()).thenReturn(pluginName);
        final RegisteredListener listener = mock(RegisteredListener.class);
        when(listener.getPlugin()).thenReturn(plugin);
        return listener;
    }

    @Test
    void anUnchangedListenerArrayIsNotWalkedAgain() {
        final RegisteredListener chatFilter = listenerOf("ChatFilter");
        final HandlerList handlers = mock(HandlerList.class);
        final RegisteredListener[] baked = {chatFilter};
        when(handlers.getRegisteredListeners()).thenReturn(baked);

        assertEquals(1, LegacyListenerNotice.report(handlers, "PlayerChatEvent", "somewhere", this.log::add));
        for (int message = 0; message < 1000; message++) {
            assertEquals(0, LegacyListenerNotice.report(handlers, "PlayerChatEvent", "somewhere", this.log::add));
        }
        verify(chatFilter, times(1)).getPlugin(); // walked once, not per message
        assertEquals(1, this.log.size());
        assertTrue(this.log.get(0).contains("ChatFilter") && this.log.get(0).contains("PlayerChatEvent"), this.log.get(0));
    }

    @Test
    void aPluginThatRegistersLaterIsReportedOnceAndEarlierOnesAreNotRepeated() {
        final RegisteredListener first = listenerOf("First");
        final RegisteredListener later = listenerOf("Later");
        final HandlerList handlers = mock(HandlerList.class);
        when(handlers.getRegisteredListeners()).thenReturn(new RegisteredListener[] {first});
        LegacyListenerNotice.report(handlers, "ChatEvent", "x", this.log::add);
        when(handlers.getRegisteredListeners()).thenReturn(new RegisteredListener[] {first, later}); // rebaked array
        assertEquals(1, LegacyListenerNotice.report(handlers, "ChatEvent", "x", this.log::add));
        assertEquals(2, this.log.size());
        assertTrue(this.log.get(1).contains("Later"), this.log.get(1));
    }

    /**
     * Re-review finding 9: the notice kept the last walked listener array strongly, pinning the
     * listeners (and their plugin's class loader) of a plugin that unregistered until the event fired
     * again. Once the handler list re-bakes, the old array must be collectable.
     */
    @Test
    void theLastWalkedArrayDoesNotPinTheListeners() throws InterruptedException {
        final RegisteredListener[][] current = {{listenerOf("Unloaded")}};
        final HandlerList handlers = new HandlerList() {
            @Override
            public RegisteredListener[] getRegisteredListeners() {
                return current[0];
            }
        };
        final WeakReference<RegisteredListener[]> old = new WeakReference<>(current[0]);
        assertEquals(1, LegacyListenerNotice.report(handlers, "PlayerChatEvent", "x", this.log::add));
        current[0] = new RegisteredListener[0]; // the plugin unregistered: the list re-baked
        for (int attempt = 0; attempt < 200 && old.get() != null; attempt++) {
            System.gc();
            Thread.sleep(10L);
        }
        assertTrue(old.get() == null, "the notice still holds the unregistered plugin's listener array");
        assertEquals(0, LegacyListenerNotice.report(handlers, "PlayerChatEvent", "x", this.log::add));
    }
}
