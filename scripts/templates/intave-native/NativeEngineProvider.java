package dev.yanianz.intave.integration;

import dev.yanianz.intave.IntaveEngine;
import dev.yanianz.intave.NativeIntave;
import dev.yanianz.intave.library.pledge.TickEnd;
import net.minecraft.server.level.ServerPlayer;
import ca.spottedleaf.moonrise.common.util.TickThread;
import java.util.concurrent.CompletionStage;
import org.bukkit.Bukkit;
import org.bukkit.event.Listener;
import org.bukkit.event.EventHandler;
import org.bukkit.event.player.PlayerQuitEvent;

/** Compiled into the server by the private build; no plugin artifact or loader entrypoint. */
public final class NativeEngineProvider implements NativeIntave.Engine {
    private IntaveEngine engine;
    private NativeProtocol protocol;

    @Override public void start() throws Exception {
        if (Bukkit.getPluginManager().getPlugin("Intave") != null || Bukkit.getPluginManager().getPlugin("ProtocolLib") != null) {
            throw new IllegalStateException("Native Intave requires isolation from Intave/ProtocolLib plugins");
        }
        for (String type : new String[]{"ac.intave.samples.share.ClockState", "ac.intave.samples.event.TimeEvent",
                "ac.intave.cloud.protocol.Packet", "net.bytebuddy.ByteBuddy", "com.comphenix.protocol.events.PacketContainer"}) {
            Class.forName(type);
        }
        engine = new IntaveEngine();
        engine.stage2();
        protocol = new NativeProtocol(engine);
        engine.registerNativeCommand();
        Bukkit.getPluginManager().registerEvents(new Listener() {
            @EventHandler public void quit(PlayerQuitEvent event) { TickEnd.forget(event.getPlayer()); }
        }, engine);
        engine.onLoad();
        engine.onEnable();
    }
    @Override public CompletionStage<Void> initialization() { return engine.nativeInitialization(); }
    @Override public boolean ready() {
        return engine != null && engine.nativeReady() && protocol != null && protocol.hasListeners();
    }
    @Override public void ownerTick(Object value) {
        ServerPlayer player = (ServerPlayer) value;
        if (!TickThread.isTickThreadFor(player)) throw new IllegalStateException("Intave tick is outside player owner region");
        TickEnd.ownerTick(player.getBukkitEntity());
    }
    @Override public void stopAdmission() {
        if (engine != null) engine.stopNativeAdmission();
    }
    @Override public void close() throws Exception {
        stopAdmission();
        // If a callback cannot drain, retain its module resources and report failed cleanup.
        // Disposing them in finally while that callback still runs would violate ownership.
        if (protocol != null) protocol.quiesce();
        try {
            if (engine != null) engine.performShutdown();
        }
        finally {
            try { if (protocol != null) protocol.close(); }
            finally {
                try { if (engine != null) engine.closeNativeOwner(); }
                finally { TickEnd.clear(); }
            }
        }
    }
}
