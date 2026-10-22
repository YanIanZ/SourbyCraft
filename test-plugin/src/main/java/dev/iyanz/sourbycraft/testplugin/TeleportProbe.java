package dev.iyanz.sourbycraft.testplugin;

import io.papermc.paper.command.brigadier.BasicCommand;
import io.papermc.paper.command.brigadier.CommandSourceStack;
import java.util.Locale;
import java.util.concurrent.atomic.AtomicReference;
import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.World;
import org.bukkit.entity.ArmorStand;
import org.bukkit.entity.Entity;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.entity.EntityTeleportEvent;
import org.bukkit.plugin.Plugin;

/**
 * {@code /tpprobe}: drives the synchronous Bukkit {@code Entity#teleport} through every route of
 * SourbyCraft's owned teleport and logs one {@code TPPROBE} line per step, so a test-server run can
 * show what a plugin sees. Registered only with {@code -Dsourby.testplugin.probes=true}; CI boots
 * without it.
 *
 * <ul>
 *   <li>near: on the stand's region, a loaded spot 3 blocks away: expect {@code true} and the
 *       stand already there when the call returns.</li>
 *   <li>far: same world, 20000 blocks away (another region, not loaded): expect {@code false},
 *       the stand moved later.</li>
 *   <li>world: the second world: expect {@code false}, the stand there later.</li>
 *   <li>offthread: from an async thread: expect {@code false}, moved later.</li>
 *   <li>redirect: an EntityTeleportEvent listener sends a near teleport 20000 blocks away: expect
 *       {@code false} (rerouted after the event), the stand there later.</li>
 * </ul>
 */
final class TeleportProbe implements BasicCommand, Listener {

    private static final double FAR = 20_000.0;

    private final Plugin plugin;
    private final AtomicReference<Entity> redirect = new AtomicReference<>();
    private final AtomicReference<Entity> cancel = new AtomicReference<>();
    private final AtomicReference<Entity> nested = new AtomicReference<>();
    private final AtomicReference<Entity> mount = new AtomicReference<>();

    TeleportProbe(final Plugin plugin) {
        this.plugin = plugin;
    }

    @Override
    public void execute(final CommandSourceStack source, final String[] args) {
        if (args.length == 2 && args[0].equals("player")) {
            this.player(args[1]);
            return;
        }
        final World world = Bukkit.getWorlds().getFirst();
        final Location spawn = world.getSpawnLocation().clone().add(0.5, 1.0, 0.5);
        Bukkit.getRegionScheduler().run(this.plugin, spawn, task -> {
            log("start thread=" + Thread.currentThread().getName());
            this.near(spawn);
            this.far(spawn);
            this.otherWorld(spawn);
            this.offThread(spawn);
            this.redirect(spawn);
            this.cancel(spawn);
            this.nested(spawn);
            this.mount(spawn);
            this.flags(spawn);
        });
    }

    /** {@code /tpprobe player <name>}: a player's sync teleport on its own region, then a far one. */
    private void player(final String name) {
        final org.bukkit.entity.Player player = Bukkit.getPlayerExact(name);
        if (player == null) {
            log("player " + name + " not online");
            return;
        }
        player.getScheduler().run(this.plugin, task -> {
            final Location near = player.getLocation().clone().add(2.0, 0.0, 0.0);
            final boolean nearResult = player.teleport(near);
            log("player-near result=" + nearResult + " movedWhenReturned=" + same(player.getLocation(), near)
                + " thread=" + Thread.currentThread().getName());
            final Location far = new Location(player.getWorld(), 30_000.5, 150.0, 0.5);
            final boolean farResult = player.teleport(far);
            log("player-far result=" + farResult);
            player.getScheduler().runDelayed(this.plugin, later -> log("player-far-later at=" + fmt(player.getLocation())),
                () -> log("player-far-later retired"), 200L);
        }, () -> log("player retired"));
        // From a thread that owns nothing: no player state may be read before scheduling.
        Bukkit.getAsyncScheduler().runNow(this.plugin, task ->
            log("player-offthread result=" + player.teleport(new Location(player.getWorld(), 30_010.5, 150.0, 0.5))));
    }

    /** A cancelled EntityTeleportEvent: false, not moved. */
    private void cancel(final Location spawn) {
        final ArmorStand stand = this.stand(spawn, "cancel");
        this.cancel.set(stand);
        final Location target = spawn.clone().add(1.0, 0.0, 4.0);
        final boolean result = stand.teleport(target);
        log("cancel result=" + result + " movedWhenReturned=" + same(stand.getLocation(), target));
        stand.remove();
    }

    /** A listener starts its own teleportAsync of the same entity: the outer sync teleport must not resume. */
    private void nested(final Location spawn) {
        final ArmorStand stand = this.stand(spawn, "nested");
        this.nested.set(stand);
        final Location outer = spawn.clone().add(4.0, 0.0, 1.0);
        final boolean result = stand.teleport(outer);
        log("nested result=" + result + " atOuterWhenReturned=" + same(stand.getLocation(), outer));
        this.later(stand, new Location(spawn.getWorld(), spawn.getX() + FAR, 130.0, spawn.getZ() + 64.0), "nested");
    }

    /** A listener mounts a new passenger during the event: no refusal, the vehicle moves (true). */
    private void mount(final Location spawn) {
        final ArmorStand stand = this.stand(spawn, "mount");
        this.mount.set(stand);
        final Location target = spawn.clone().add(-3.0, 0.0, 2.0);
        boolean result;
        try {
            result = stand.teleport(target);
        } catch (final RuntimeException e) {
            log("mount threw " + e);
            return;
        }
        log("mount result=" + result + " passengers=" + stand.getPassengers().size()
            + " passengerAt=" + (stand.getPassengers().isEmpty() ? "-" : fmt(stand.getPassengers().getFirst().getLocation())));
        stand.getPassengers().forEach(Entity::remove);
        stand.remove();
    }

    /** Velocity flags on the synchronous path keep the component; without them the entity stops. */
    private void flags(final Location spawn) {
        final ArmorStand stand = this.stand(spawn, "flags");
        stand.setVelocity(new org.bukkit.util.Vector(0.4, 0.0, 0.0));
        stand.teleport(spawn.clone().add(-2.0, 0.0, -2.0), org.bukkit.event.player.PlayerTeleportEvent.TeleportCause.PLUGIN,
            io.papermc.paper.entity.TeleportFlag.Relative.VELOCITY_X);
        final double kept = stand.getVelocity().getX();
        stand.teleport(spawn.clone().add(-2.0, 0.0, -3.0));
        log(String.format(Locale.ROOT, "flags keptVx=%.2f afterNoFlagVx=%.2f", kept, stand.getVelocity().getX()));
        stand.remove();
    }

    private ArmorStand stand(final Location at, final String name) {
        final ArmorStand stand = at.getWorld().spawn(at, ArmorStand.class);
        stand.setGravity(false);
        stand.setPersistent(true); // survives the chunk unloading before the later check
        stand.customName(net.kyori.adventure.text.Component.text("tpprobe-" + name));
        return stand;
    }

    private void near(final Location spawn) {
        final ArmorStand stand = this.stand(spawn, "near");
        final Location target = spawn.clone().add(3.0, 0.0, 3.0);
        final boolean result = stand.teleport(target);
        log("near result=" + result + " movedWhenReturned=" + same(stand.getLocation(), target)
            + " at=" + fmt(stand.getLocation()));
        stand.remove();
    }

    private void far(final Location spawn) {
        final ArmorStand stand = this.stand(spawn, "far");
        final Location target = new Location(spawn.getWorld(), spawn.getX() + FAR, 120.0, spawn.getZ());
        final boolean result = stand.teleport(target);
        log("far result=" + result + " movedWhenReturned=" + same(stand.getLocation(), target));
        this.later(stand, target, "far");
    }

    private void otherWorld(final Location spawn) {
        if (Bukkit.getWorlds().size() < 2) {
            log("world skipped: one world loaded");
            return;
        }
        final World other = Bukkit.getWorlds().get(1);
        final ArmorStand stand = this.stand(spawn, "world");
        final Location target = new Location(other, 0.5, 100.0, 0.5);
        final boolean result = stand.teleport(target);
        log("world result=" + result + " movedWhenReturned=" + same(stand.getLocation(), target));
        this.later(stand, target, "world");
    }

    private void offThread(final Location spawn) {
        final ArmorStand stand = this.stand(spawn, "offthread");
        final Location target = spawn.clone().add(5.0, 0.0, 5.0);
        Bukkit.getAsyncScheduler().runNow(this.plugin, task -> {
            final boolean result = stand.teleport(target);
            log("offthread result=" + result + " thread=" + Thread.currentThread().getName());
            this.later(stand, target, "offthread");
        });
    }

    private void redirect(final Location spawn) {
        final ArmorStand stand = this.stand(spawn, "redirect");
        this.redirect.set(stand);
        final Location asked = spawn.clone().add(2.0, 0.0, 2.0);
        final Location redirected = new Location(spawn.getWorld(), spawn.getX() - FAR, 120.0, spawn.getZ());
        final boolean result = stand.teleport(asked);
        log("redirect result=" + result + " movedWhenReturned=" + same(stand.getLocation(), asked));
        this.later(stand, redirected, "redirect");
    }

    @EventHandler
    public void onTeleport(final EntityTeleportEvent event) {
        if (event.getEntity() == this.redirect.get()) {
            this.redirect.set(null);
            final Location from = event.getFrom();
            event.setTo(new Location(from.getWorld(), from.getX() - FAR, 120.0, from.getZ()));
            log("redirect event fired thread=" + Thread.currentThread().getName());
        } else if (event.getEntity() == this.cancel.get()) {
            this.cancel.set(null);
            event.setCancelled(true);
        } else if (event.getEntity() == this.nested.get()) {
            this.nested.set(null);
            final Location from = event.getFrom();
            event.getEntity().teleportAsync(new Location(from.getWorld(), from.getX() + FAR, 130.0, from.getZ() + 64.0));
            log("nested listener started teleportAsync");
        } else if (event.getEntity() == this.mount.get()) {
            this.mount.set(null);
            final ArmorStand rider = this.stand(event.getFrom(), "rider");
            event.getEntity().addPassenger(rider);
            log("mount listener added a passenger");
        }
    }

    /**
     * Looks again after 100 ticks on the region that owns the target (an async cross-region move
     * replaces the entity object, so its own scheduler retires) and finds the stand by name.
     */
    private void later(final Entity entity, final Location target, final String step) {
        Bukkit.getGlobalRegionScheduler().runDelayed(this.plugin, delay -> target.getWorld().getChunkAtAsync(target).thenAccept(chunk -> {
            // Hold the chunk (FULL, entities loaded) while looking; the stand is persistent.
            Bukkit.getRegionScheduler().run(this.plugin, target, hold -> chunk.addPluginChunkTicket(this.plugin));
            this.look(target, step, 1);
        }), 100L);
    }

    /** Looks for the named stand at the target every second, up to 15 times. */
    private void look(final Location target, final String step, final int attempt) {
        final String name = "tpprobe-" + step;
        Bukkit.getRegionScheduler().runDelayed(this.plugin, target, task -> {
            final var found = target.getWorld().getNearbyEntities(target, 2.0, 2.0, 2.0).stream()
                .filter(e -> e.customName() != null
                    && net.kyori.adventure.text.serializer.plain.PlainTextComponentSerializer.plainText().serialize(e.customName()).equals(name))
                .toList();
            if (found.isEmpty() && attempt < 15) {
                this.look(target, step, attempt + 1);
                return;
            }
            log(step + "-later arrived=" + !found.isEmpty() + " attempt=" + attempt
                + (found.isEmpty() ? "" : " at=" + fmt(found.getFirst().getLocation())));
            found.forEach(Entity::remove);
            target.getChunk().removePluginChunkTicket(this.plugin);
        }, 20L);
    }

    private static boolean same(final Location a, final Location b) {
        return a.getWorld() == b.getWorld() && a.distanceSquared(b) < 0.01;
    }

    private static String fmt(final Location l) {
        return String.format(Locale.ROOT, "%s %.1f %.1f %.1f", l.getWorld().getName(), l.getX(), l.getY(), l.getZ());
    }

    private static void log(final String message) {
        Bukkit.getLogger().info("TPPROBE " + message);
    }
}
