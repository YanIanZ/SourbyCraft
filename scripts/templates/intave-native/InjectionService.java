package dev.yanianz.intave.module.linker.packet.tinyprotocol;

import com.comphenix.protocol.PacketType;
import dev.yanianz.intave.IntaveEngine;
import dev.yanianz.intave.integration.NativeProtocol;
import dev.yanianz.intave.module.linker.packet.FilteringPacketAdapter;
import org.bukkit.entity.Player;
import java.util.Collection;
import java.util.List;
import java.util.concurrent.ConcurrentHashMap;

/** Native channel initialization replaces plugin listeners and TinyProtocol reflection. */
public final class InjectionService {
    private final ConcurrentHashMap<PacketType, Collection<FilteringPacketAdapter>> subscriptions = new ConcurrentHashMap<>();
    public InjectionService(IntaveEngine engine) { NativeProtocol.current(); }
    public void setupSubscriptions(PacketType type, Collection<FilteringPacketAdapter> subscriptions) {
        Collection<FilteringPacketAdapter> snapshot = List.copyOf(subscriptions);
        this.subscriptions.put(type, snapshot);
        NativeProtocol.current().internalSubscriptions(type, snapshot);
    }
    public Collection<FilteringPacketAdapter> subscriptionsOf(PacketType type) { return subscriptions.get(type); }
    public void reset() { NativeProtocol.current().clearInternalSubscriptions(); subscriptions.clear(); }
    public void injectAll() { }
    public void inject(Player player) { }
    public void uninjectAll() { }
    public void uninject(Player player) { }
}
