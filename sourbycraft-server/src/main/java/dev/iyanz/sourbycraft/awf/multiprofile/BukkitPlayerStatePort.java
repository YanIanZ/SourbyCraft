package dev.iyanz.sourbycraft.awf.multiprofile;

import dev.iyanz.sourbycraft.util.SourbyLogger;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.function.BooleanSupplier;
import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.NamespacedKey;
import org.bukkit.World;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.PlayerInventory;
import org.bukkit.plugin.Plugin;
import org.bukkit.potion.PotionEffect;
import org.bukkit.potion.PotionEffectType;

/**
 * The Paper implementation of {@link PlayerStatePort}.
 *
 * <p>Every read or write runs inside one {@code EntityScheduler} task for the player, which is the
 * only place a Bukkit player may be touched, or synchronously on a thread that already owns the
 * player ({@link #captureOnOwnerThread}). A capture is a single task, so a region tick cannot
 * interleave half an inventory; the gap between a switch's capture and its apply is covered by the
 * runtime's freeze listener, not by this class. The port never blocks the entity thread on I/O: it
 * only builds bytes and completes a future that the engine consumes on its I/O worker.</p>
 *
 * <p>Item stacks are encoded with Paper's {@code ItemStack.serializeAsBytes()}, which keeps data
 * components intact; they are decoded with {@code deserializeBytes} only after the snapshot passed
 * its bounds and checksum checks, so a corrupt file cannot construct an arbitrary object graph.</p>
 */
public final class BukkitPlayerStatePort implements PlayerStatePort {

    /** First armor slot (boots) in a pre-Build-50 inventory section; 36-39 hold boots to helmet. */
    private static final int LEGACY_ARMOR_SLOT = 36;
    /** The off-hand slot in a pre-Build-50 inventory section. */
    private static final int LEGACY_OFFHAND_SLOT = 40;

    private final Plugin plugin;
    private final ProfileStateAdapters adapters;

    public BukkitPlayerStatePort(final Plugin plugin, final ProfileStateAdapters adapters) {
        this.plugin = Objects.requireNonNull(plugin, "plugin");
        this.adapters = Objects.requireNonNull(adapters, "adapters");
    }

    @Override
    public boolean online(final UUID playerId) {
        return Bukkit.getPlayer(playerId) != null;
    }

    @Override
    public CompletableFuture<ProfileState> capture(final UUID playerId, final ProfileIsolation isolation) {
        final Player player = Bukkit.getPlayer(playerId);
        if (player == null) return CompletableFuture.failedFuture(new IllegalStateException("player is not online"));
        return onEntity(player, () -> captureNow(player, isolation), "capture");
    }

    /**
     * Captures on the calling thread, which must own the player. The quit listener uses it: inside
     * {@code PlayerQuitEvent} the player still exists, while an entity task scheduled from there
     * would run after the player was removed.
     *
     * @throws IllegalStateException when the calling thread does not own the player
     */
    public ProfileState captureOnOwnerThread(final Player player, final ProfileIsolation isolation) {
        if (!Bukkit.isOwnedByCurrentRegion(player)) {
            throw new IllegalStateException("the profile capture of " + player.getUniqueId()
                + " must run on the thread that owns the player");
        }
        return captureNow(player, isolation);
    }

    @Override
    public CompletableFuture<Void> apply(final UUID playerId, final ProfileState state,
                                         final ProfileIsolation isolation) {
        return apply(playerId, state, isolation, () -> false);
    }

    @Override
    public CompletableFuture<Void> apply(final UUID playerId, final ProfileState state,
                                         final ProfileIsolation isolation, final BooleanSupplier abandoned) {
        final Player player = Bukkit.getPlayer(playerId);
        if (player == null) return CompletableFuture.failedFuture(new IllegalStateException("player is not online"));
        return onEntity(player, () -> {
            if (abandoned.getAsBoolean()) {
                throw new IllegalStateException("the profile switch was abandoned before its apply ran");
            }
            applyNow(player, state, isolation);
            return null;
        }, "apply");
    }

    @Override
    public CompletableFuture<Void> teleport(final UUID playerId, final ProfileState.Location location) {
        if (location == null) return CompletableFuture.completedFuture(null);
        final Player player = Bukkit.getPlayer(playerId);
        if (player == null) return CompletableFuture.failedFuture(new IllegalStateException("player is not online"));
        final World world = Bukkit.getWorld(location.world());
        if (world == null) {
            return CompletableFuture.failedFuture(new IllegalStateException("world '" + location.world()
                + "' is not loaded"));
        }
        final Location destination = new Location(world, location.x(), location.y(), location.z(), location.yaw(),
            location.pitch());
        return player.teleportAsync(destination).thenAccept(moved -> {
            if (!Boolean.TRUE.equals(moved)) throw new IllegalStateException("the teleport was refused");
        });
    }

    @Override
    public ProfileState carryAdapters(final ProfileState captured, final ProfileState previous) {
        return captured.withAdapters(this.adapters.carryForward(captured.adapters(), previous.adapters()));
    }

    /**
     * Reads the player on the thread that owns them. Only the player's own state is read: the respawn
     * point is taken as stored ({@code getPotentialRespawnLocation}), because validating it would read
     * the bed or anchor block, which may lie in another region or world.
     */
    private ProfileState captureNow(final Player player, final ProfileIsolation isolation) {
        final PlayerInventory inventory = player.getInventory();
        final List<ProfileState.Item> storage = isolation.inventory() ? items(inventory.getStorageContents()) : List.of();
        final List<ProfileState.Item> armor = isolation.armor() ? items(inventory.getArmorContents()) : List.of();
        final List<ProfileState.Item> offhand = isolation.armor() ? item(0, inventory.getItemInOffHand()) : List.of();
        final List<ProfileState.Item> ender = isolation.enderChest() ? items(player.getEnderChest().getContents())
            : List.of();
        final int level = isolation.experience() ? player.getLevel() : 0;
        final float experience = isolation.experience() ? player.getExp() : 0.0f;
        final double health = isolation.health() ? player.getHealth() : 0.0;
        final int food = isolation.food() ? player.getFoodLevel() : 0;
        final float saturation = isolation.food() ? player.getSaturation() : 0.0f;
        final List<ProfileState.Effect> effects = new ArrayList<>();
        if (isolation.potionEffects()) {
            for (final PotionEffect effect : player.getActivePotionEffects()) {
                if (effects.size() == ProfileState.MAX_EFFECTS) {
                    SourbyLogger.warn("AWF MultiProfile: " + player.getUniqueId() + " has more than "
                        + ProfileState.MAX_EFFECTS + " active effects; only the first " + ProfileState.MAX_EFFECTS
                        + " are stored in the profile");
                    break;
                }
                final ProfileState.Effect stored = storedEffect(player, effect);
                if (stored != null) effects.add(stored);
            }
        }
        final ProfileState.Location spawn = isolation.location() ? location(player.getPotentialRespawnLocation()) : null;
        final ProfileState.Location returnPoint = isolation.location() ? location(player.getLocation()) : null;
        return new ProfileState(ProfileState.SCHEMA, storage, armor, offhand, ender, level, experience, health, food,
            saturation, effects, spawn, returnPoint, this.adapters.capture(player));
    }

    /** One active effect as profile state, or {@code null}, with a warning, when the profile cannot hold it. */
    private static ProfileState.Effect storedEffect(final Player player, final PotionEffect effect) {
        try {
            return new ProfileState.Effect(effect.getType().getKey().toString(), effect.getDuration(),
                effect.getAmplifier(), effect.isAmbient(), effect.hasParticles(), effect.hasIcon());
        } catch (final RuntimeException unstorable) {
            SourbyLogger.warn("AWF MultiProfile: the effect " + effect + " of " + player.getUniqueId()
                + " cannot be stored in the profile (" + unstorable.getMessage() + ") and was skipped");
            return null;
        }
    }

    private void applyNow(final Player player, final ProfileState state, final ProfileIsolation isolation) {
        final PlayerInventory inventory = player.getInventory();
        // Snapshots written before Build 50 stored the whole inventory, armor and off-hand included, in
        // the inventory section (slots 36-39 armor, 40 off-hand); storage is slots 0-35.
        final List<ProfileState.Item> legacyEquipment = new ArrayList<>();
        for (final ProfileState.Item item : state.inventory()) {
            if (item.slot() >= LEGACY_ARMOR_SLOT && item.slot() <= LEGACY_OFFHAND_SLOT) legacyEquipment.add(item);
        }
        if (isolation.inventory()) {
            final ItemStack[] storage = new ItemStack[inventory.getStorageContents().length];
            for (final ProfileState.Item item : state.inventory()) {
                if (item.slot() < storage.length && item.slot() < LEGACY_ARMOR_SLOT) storage[item.slot()] = decode(item);
            }
            if (!isolation.armor()) stowLegacyEquipment(player, legacyEquipment, storage);
            inventory.setStorageContents(storage);
        }
        if (isolation.armor()) {
            final ItemStack[] armor = new ItemStack[4];
            ItemStack offhand = null;
            if (!state.armor().isEmpty() || !state.offhand().isEmpty()) {
                for (final ProfileState.Item item : state.armor()) {
                    if (item.slot() < armor.length) armor[item.slot()] = decode(item);
                }
                if (!state.offhand().isEmpty()) offhand = decode(state.offhand().getFirst());
            } else {
                // A pre-Build-50 snapshot taken while armor isolation was off has its armor and
                // off-hand only in the legacy inventory slots, which were then the profile's.
                for (final ProfileState.Item item : legacyEquipment) {
                    if (item.slot() == LEGACY_OFFHAND_SLOT) {
                        offhand = decode(item);
                    } else {
                        armor[item.slot() - LEGACY_ARMOR_SLOT] = decode(item);
                    }
                }
            }
            inventory.setArmorContents(armor);
            inventory.setItemInOffHand(offhand);
        }
        if (isolation.enderChest()) {
            final ItemStack[] ender = new ItemStack[player.getEnderChest().getContents().length];
            for (final ProfileState.Item item : state.enderChest()) {
                if (item.slot() < ender.length) ender[item.slot()] = decode(item);
            }
            player.getEnderChest().setContents(ender);
        }
        if (isolation.experience()) {
            player.setLevel(state.level());
            player.setExp(Math.max(0.0f, Math.min(1.0f, state.experience())));
        }
        if (isolation.health() && state.health() > 0.0) {
            player.setHealth(Math.min(state.health(), player.getMaxHealth()));
        }
        if (isolation.food()) {
            player.setFoodLevel(Math.max(0, Math.min(20, state.food())));
            player.setSaturation(Math.max(0.0f, state.saturation()));
        }
        if (isolation.potionEffects()) {
            for (final PotionEffect active : player.getActivePotionEffects()) player.removePotionEffect(active.getType());
            for (final ProfileState.Effect effect : state.effects()) applyEffect(player, effect);
        }
        if (isolation.location()) applyRespawn(player, state.spawn());
        this.adapters.apply(player, state.adapters());
    }

    /**
     * Legacy armor and off-hand items of a pre-Build-50 snapshot, applied while armor isolation is off:
     * the armor the player wears no longer belongs to the profile and stays, so these items, which the
     * profile owned when the snapshot was written, go into free storage slots. Storage is applied as a
     * whole, so a rollback that restores the previous storage removes them again and they stay in the
     * snapshot. Items that do not fit are not restored and are reported.
     */
    private static void stowLegacyEquipment(final Player player, final List<ProfileState.Item> legacy,
                                            final ItemStack[] storage) {
        int free = 0;
        int left = 0;
        for (final ProfileState.Item item : legacy) {
            while (free < storage.length && storage[free] != null) free++;
            if (free == storage.length) {
                left++;
                continue;
            }
            storage[free] = decode(item);
        }
        if (left > 0) {
            SourbyLogger.warn("AWF MultiProfile: " + left + " armor or off-hand item(s) of a pre-Build-50 snapshot of "
                + player.getUniqueId() + " did not fit into the free inventory slots and were not restored;"
                + " they remain in that snapshot until the profile is saved again");
        }
    }

    /** Adds one stored effect; one that cannot be applied any more is skipped with a warning, never fatal. */
    private static void applyEffect(final Player player, final ProfileState.Effect effect) {
        try {
            final NamespacedKey key = NamespacedKey.fromString(effect.typeKey());
            final PotionEffectType type = key == null ? null : PotionEffectType.getByKey(key);
            if (type == null) {
                // A version that removed the effect: skip it, do not fail the switch.
                SourbyLogger.warn("AWF MultiProfile: the stored effect " + effect.typeKey() + " of "
                    + player.getUniqueId() + " does not exist on this server and was not applied");
                return;
            }
            player.addPotionEffect(new PotionEffect(type, effect.durationTicks(), effect.amplifier(),
                effect.ambient(), effect.particles(), effect.icon()));
        } catch (final RuntimeException refused) {
            SourbyLogger.warn("AWF MultiProfile: the stored effect " + effect.typeKey() + " of " + player.getUniqueId()
                + " could not be applied (" + refused.getMessage() + ")");
        }
    }

    /**
     * Gives the player the profile's respawn point, or none when the profile has none. Setting it reads
     * no block (the bed or anchor may lie in another region); the server checks the block only when the
     * player respawns. The snapshot does not record whether the original point was forced, so it is
     * set as a forced one ({@code force}, vanilla's {@code forced} flag): a point set by
     * {@code /spawnpoint} or a plugin keeps working, but a restored bed or respawn anchor point is also
     * used after its block is gone (when that spot is free), and a respawn anchor works without using
     * or needing charge, until the player sets a new respawn point.
     * A respawn point in a world that is not loaded cannot be set: it is cleared, with a warning, and
     * the player respawns at the world spawn, as when a bed is gone.
     */
    private static void applyRespawn(final Player player, final ProfileState.Location spawn) {
        if (spawn == null) {
            player.setRespawnLocation(null);
            return;
        }
        final World world = Bukkit.getWorld(spawn.world());
        if (world == null) {
            SourbyLogger.warn("AWF MultiProfile: the respawn point of " + player.getUniqueId() + " is in world '"
                + spawn.world() + "', which is not loaded; it was cleared");
            player.setRespawnLocation(null);
            return;
        }
        player.setRespawnLocation(new Location(world, spawn.x(), spawn.y(), spawn.z(), spawn.yaw(), spawn.pitch()), true);
    }

    private static List<ProfileState.Item> items(final ItemStack[] contents) {
        final List<ProfileState.Item> items = new ArrayList<>();
        for (int slot = 0; slot < contents.length; slot++) {
            final ItemStack stack = contents[slot];
            if (stack != null && !stack.getType().isAir()) {
                items.add(new ProfileState.Item(slot, stack.serializeAsBytes()));
            }
        }
        return items;
    }

    private static List<ProfileState.Item> item(final int slot, final ItemStack stack) {
        if (stack == null || stack.getType().isAir()) return List.of();
        return List.of(new ProfileState.Item(slot, stack.serializeAsBytes()));
    }

    private static ItemStack decode(final ProfileState.Item item) {
        return ItemStack.deserializeBytes(item.data());
    }

    private static ProfileState.Location location(final Location location) {
        if (location == null || location.getWorld() == null) return null;
        final String world = location.getWorld().getName().toLowerCase(java.util.Locale.ROOT);
        if (!world.matches("[a-z0-9_-]{1,48}")) return null;   // a non-AWF world cannot be a profile destination
        return new ProfileState.Location(world, location.getX(), location.getY(), location.getZ(), location.getYaw(),
            location.getPitch());
    }

    private <T> CompletableFuture<T> onEntity(final Player player, final java.util.function.Supplier<T> work,
                                              final String what) {
        final CompletableFuture<T> future = new CompletableFuture<>();
        try {
            final var task = player.getScheduler().run(this.plugin, scheduled -> {
                try {
                    future.complete(work.get());
                } catch (final Throwable failed) {
                    future.completeExceptionally(failed);
                }
            }, () -> future.completeExceptionally(new IllegalStateException(
                "the player was removed before the profile " + what + " could run")));
            if (task == null) {
                future.completeExceptionally(new IllegalStateException(
                    "the player's scheduler is retired; profile " + what + " was not run"));
            }
        } catch (final RuntimeException refused) {
            future.completeExceptionally(refused);
        }
        return future;
    }
}
