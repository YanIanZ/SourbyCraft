package dev.iyanz.sourbycraft.bridge;

import net.minecraft.server.MinecraftServer;

/**
 * Keeps Paper's {@code public static int MinecraftServer.currentTick} for plugins that read it directly.
 *
 * <p>Paper increments the field once per server tick; the region-threading baseline removed it, so a plugin that
 * links against it (FastAsyncWorldEdit 2.15.4 and 2.16.1, {@code PaperweightFaweWorldNativeAccess} of the
 * {@code v26_2} adapter, a {@code getstatic}) failed with {@link NoSuchFieldError}, and every plugin built on it
 * (WorldGuard, then Jobs) failed with it. MC feature patch 0083 declares the field again and calls
 * {@link #globalTick()} from the global tick handle's {@code tickRegion}.
 *
 * <p>The value is a <b>global-tick counter</b>, not a region's tick: it counts global ticks since start (0 during
 * startup, before the region scheduler runs), increases by one per global tick and wraps like Paper's {@code int}.
 * Region threads and async threads see it only approximately current (the global tick and the regions are not in
 * lockstep); use it only as "has time passed", never as a region's game time, and never compare it with
 * {@code Bukkit.getCurrentTick()} (the current region's tick). Nothing in the server reads it: Paper's own former
 * readers ({@code Connection}, {@code ServerPlayerGameMode}) stay commented out in the baseline.
 */
public final class LegacyServerTick {

    private LegacyServerTick() {
    }

    /** Advances the counter. Called once per global tick on the global tick thread, the field's only writer. */
    public static void globalTick() {
        MinecraftServer.currentTick = MinecraftServer.currentTick + 1;
    }

    /** The current value, as a plugin reading the field sees it. */
    public static int current() {
        return MinecraftServer.currentTick;
    }
}
