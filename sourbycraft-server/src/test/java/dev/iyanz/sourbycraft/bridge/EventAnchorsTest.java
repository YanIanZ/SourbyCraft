package dev.iyanz.sourbycraft.bridge;

import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;

import java.lang.reflect.Proxy;
import org.bukkit.block.Block;
import org.bukkit.entity.Entity;
import org.bukkit.entity.HumanEntity;
import org.bukkit.entity.Player;
import org.bukkit.entity.Zombie;
import org.bukkit.event.Event;
import org.bukkit.event.HandlerList;
import org.bukkit.event.block.BlockBreakEvent;
import org.bukkit.event.entity.EntityEvent;
import org.bukkit.event.inventory.InventoryEvent;
import org.bukkit.event.player.PlayerEvent;
import org.bukkit.event.player.PlayerKickEvent;
import org.bukkit.event.player.PlayerLoginEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.InventoryView;
import org.junit.jupiter.api.Test;

/** Which player an event anchors a legacy task to; players only, and never a leaving one. */
class EventAnchorsTest {

    private static final HandlerList HANDLERS = new HandlerList();

    @SuppressWarnings("unchecked")
    private static <T> T proxy(final Class<T> type, final Object... extra) {
        final Class<?>[] types = new Class<?>[extra.length + 1];
        types[0] = type;
        for (int i = 0; i < extra.length; i++) types[i + 1] = (Class<?>)extra[i];
        return (T)Proxy.newProxyInstance(type.getClassLoader(), types, (self, method, args) -> switch (method.getName()) {
            case "hashCode" -> System.identityHashCode(self);
            case "equals" -> self == args[0];
            case "toString" -> type.getSimpleName();
            default -> throw new UnsupportedOperationException(method.getName());
        });
    }

    /** A plain player event, as most of Bukkit's are. */
    private static final class SomePlayerEvent extends PlayerEvent {
        SomePlayerEvent(final Player player) { super(player); }
        @Override public HandlerList getHandlers() { return HANDLERS; }
    }

    /** An async player event (AsyncPlayerChatEvent / AsyncChatEvent shape). */
    private static final class SomeAsyncPlayerEvent extends PlayerEvent {
        SomeAsyncPlayerEvent(final Player player) { super(player, true); }
        @Override public HandlerList getHandlers() { return HANDLERS; }
    }

    private static final class SomeEntityEvent extends EntityEvent {
        SomeEntityEvent(final Entity entity) { super(entity); }
        @Override public HandlerList getHandlers() { return HANDLERS; }
    }

    private static final class SomeInventoryEvent extends InventoryEvent {
        SomeInventoryEvent(final InventoryView view) { super(view); }
        @Override public HandlerList getHandlers() { return HANDLERS; }
    }

    /** A plugin's own event about no entity (EssentialsX {@code AsyncUserDataLoadEvent} shape). */
    private static final class SomePluginEvent extends Event {
        SomePluginEvent() { super(true); }
        @Override public HandlerList getHandlers() { return HANDLERS; }
    }

    @Test
    void playerEventsResolveToTheirPlayer() {
        final Player player = proxy(Player.class);
        assertSame(player, EventAnchors.resolve(new SomePlayerEvent(player)));
        assertSame(player, EventAnchors.resolve(new SomeAsyncPlayerEvent(player)));
    }

    @Test
    void leavingAndNotYetPlacedPlayersResolveToNothing() {
        final Player player = proxy(Player.class);
        assertNull(EventAnchors.resolve(new PlayerQuitEvent(player, (net.kyori.adventure.text.Component)null,
            PlayerQuitEvent.QuitReason.DISCONNECTED)));
        assertNull(EventAnchors.resolve(new PlayerKickEvent(player, net.kyori.adventure.text.Component.empty(),
            net.kyori.adventure.text.Component.empty())));
        assertNull(EventAnchors.resolve(new PlayerLoginEvent(player, "host", java.net.InetAddress.getLoopbackAddress())));
        assertNull(EventAnchors.resolve(new org.spigotmc.event.player.PlayerSpawnLocationEvent(player,
            new org.bukkit.Location(null, 0, 0, 0))));
    }

    @Test
    void entityEventsResolveOnlyWhenTheEntityIsAPlayer() {
        final Player player = proxy(Player.class);
        assertSame(player, EventAnchors.resolve(new SomeEntityEvent(player)));
        assertNull(EventAnchors.resolve(new SomeEntityEvent(proxy(Zombie.class))), "mobs die; never an anchor");
    }

    @Test
    void inventoryEventsResolveToTheViewer() {
        final Player player = proxy(Player.class);
        final InventoryView view = proxy(InventoryView.class);
        final InventoryView viewing = (InventoryView)Proxy.newProxyInstance(InventoryView.class.getClassLoader(),
            new Class<?>[] {InventoryView.class}, (self, method, args) -> switch (method.getName()) {
                case "getPlayer" -> player;
                case "getTopInventory", "getBottomInventory" -> proxy(Inventory.class);
                default -> view.getClass(); // unused
            });
        assertSame(player, EventAnchors.resolve(new SomeInventoryEvent(viewing)));
        final InventoryView npcView = (InventoryView)Proxy.newProxyInstance(InventoryView.class.getClassLoader(),
            new Class<?>[] {InventoryView.class}, (self, method, args) -> switch (method.getName()) {
                case "getPlayer" -> proxy(HumanEntity.class);
                default -> null;
            });
        assertNull(EventAnchors.resolve(new SomeInventoryEvent(npcView)), "a non-player viewer is no anchor");
    }

    @Test
    void blockBreakResolvesToTheBreakingPlayer() {
        final Player player = proxy(Player.class);
        assertSame(player, EventAnchors.resolve(new BlockBreakEvent(proxy(Block.class), player)));
    }

    @Test
    void eventsAboutNoPlayerAndFailingAccessorsResolveToNothing() {
        assertNull(EventAnchors.resolve(new SomePluginEvent()));
        assertNull(EventAnchors.resolve(null));
        final InventoryView throwing = (InventoryView)Proxy.newProxyInstance(InventoryView.class.getClassLoader(),
            new Class<?>[] {InventoryView.class}, (self, method, args) -> { throw new IllegalStateException("closed"); });
        assertNull(EventAnchors.resolve(new SomeInventoryEvent(throwing)), "never throws into event dispatch");
    }
}
