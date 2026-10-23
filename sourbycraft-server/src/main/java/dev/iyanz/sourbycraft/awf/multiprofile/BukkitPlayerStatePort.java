package dev.iyanz.sourbycraft.awf.multiprofile;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.NamespacedKey;
import org.bukkit.World;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;
import org.bukkit.plugin.Plugin;
import org.bukkit.potion.PotionEffect;
import org.bukkit.potion.PotionEffectType;

/**
 * The Paper implementation of {@link PlayerStatePort}.
 *
 * <p>Every read or write runs inside one {@code EntityScheduler} task for the player, which is the
 * only place a Bukkit player may be touched. "Freeze" is the absence of a gap: the whole capture is a
 * single task, so a region tick cannot interleave half an inventory. The port never blocks the entity
 * thread on I/O: it only builds bytes and completes a future that the engine consumes on its I/O
 * worker.</p>
 *
 * <p>Item stacks are encoded with Paper's {@code ItemStack.serializeAsBytes()}, which keeps data
 * components intact; they are decoded with {@code deserializeBytes} only after the snapshot passed
 * its bounds and checksum checks, so a corrupt file cannot construct an arbitrary object graph.</p>
 */
public final class BukkitPlayerStatePort implements PlayerStatePort {

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

    @Override
    public CompletableFuture<Void> apply(final UUID playerId, final ProfileState state,
                                         final ProfileIsolation isolation) {
        final Player player = Bukkit.getPlayer(playerId);
        if (player == null) return CompletableFuture.failedFuture(new IllegalStateException("player is not online"));
        return onEntity(player, () -> {
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

    private ProfileState captureNow(final Player player, final ProfileIsolation isolation) {
        final List<ProfileState.Item> inventory = isolation.inventory()
            ? items(player.getInventory().getContents()) : List.of();
        final List<ProfileState.Item> armor = isolation.armor()
            ? items(player.getInventory().getArmorContents()) : List.of();
        final List<ProfileState.Item> offhand = isolation.armor() ? item(0, player.getInventory().getItemInOffHand())
            : List.of();
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
                effects.add(new ProfileState.Effect(effect.getType().getKey().toString(), effect.getDuration(),
                    effect.getAmplifier(), effect.isAmbient(), effect.hasParticles(), effect.hasIcon()));
            }
        }
        final ProfileState.Location spawn = isolation.location() ? location(player.getRespawnLocation()) : null;
        final ProfileState.Location returnPoint = isolation.location() ? location(player.getLocation()) : null;
        return new ProfileState(ProfileState.SCHEMA, inventory, armor, offhand, ender, level, experience, health, food,
            saturation, effects, spawn, returnPoint, this.adapters.capture(player));
    }

    private void applyNow(final Player player, final ProfileState state, final ProfileIsolation isolation) {
        if (isolation.inventory()) {
            final ItemStack[] contents = new ItemStack[player.getInventory().getContents().length];
            for (final ProfileState.Item item : state.inventory()) {
                if (item.slot() < contents.length) contents[item.slot()] = decode(item);
            }
            player.getInventory().setContents(contents);
        }
        if (isolation.armor()) {
            final ItemStack[] armor = new ItemStack[4];
            for (final ProfileState.Item item : state.armor()) {
                if (item.slot() < armor.length) armor[item.slot()] = decode(item);
            }
            player.getInventory().setArmorContents(armor);
            player.getInventory().setItemInOffHand(state.offhand().isEmpty() ? null : decode(state.offhand().getFirst()));
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
            for (final ProfileState.Effect effect : state.effects()) {
                final PotionEffectType type = PotionEffectType.getByKey(NamespacedKey.fromString(effect.typeKey()));
                if (type == null) continue;      // a version that removed the effect: skip, do not fail the switch
                player.addPotionEffect(new PotionEffect(type, effect.durationTicks(), effect.amplifier(),
                    effect.ambient(), effect.particles(), effect.icon()));
            }
        }
        this.adapters.apply(player, state.adapters());
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
