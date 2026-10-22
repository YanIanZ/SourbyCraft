package dev.iyanz.sourbycraft.entity.teleport;

import dev.iyanz.sourbycraft.execution.region.RegionOwnership;
import java.util.function.Consumer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.sounds.SoundSource;
import net.minecraft.world.Difficulty;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EntitySpawnReason;
import net.minecraft.world.entity.EntityTypes;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.projectile.throwableitemprojectile.ThrownEnderpearl;
import net.minecraft.world.phys.Vec3;
import org.bukkit.Location;
import org.bukkit.craftbukkit.CraftWorld;
import org.bukkit.event.entity.CreatureSpawnEvent;
import org.bukkit.event.entity.EntityRemoveEvent;
import org.bukkit.event.player.PlayerTeleportEvent;
import org.bukkit.event.player.PlayerTeleportEvent.TeleportCause;

/** Thrown-pearl events run on the owner's region; effects use only the successful transfer's entity. */
public final class PearlTeleport {
    private PearlTeleport() {}

    /** On the pearl's region. Consumption never depends on the owner's scheduler accepting the task. */
    public static void hit(ThrownEnderpearl pearl, Entity owner, ServerLevel impactWorld, Vec3 impact) {
        RegionOwnership.current().ensureTickThread(pearl, "Ender pearl impact must run on its owning region");
        final boolean cooldown = pearl.isOnPortalCooldown();
        PearlTeleportFlow.hit(() -> {
            if (owner != null) {
                owner.getBukkitEntity().taskScheduler.scheduleOrExecute(
                    entity -> teleport(pearl, entity, impactWorld, impact, cooldown));
            }
        }, () -> pearl.discard(EntityRemoveEvent.Cause.HIT));
    }

    private static boolean valid(Entity entity) {
        // Listeners can initiate transfers or remove/disconnect the player. Check ownership first.
        return RegionOwnership.current().isTickThreadFor(entity) && !entity.isRemoved() && entity.getRegionTransferState() == null
            && (!(entity instanceof ServerPlayer player)
                || (player.connection != null && !player.hasDisconnected() && player.connection.isAcceptingMessages()));
    }

    private static boolean eligible(Entity entity, ServerLevel destination) {
        if (entity.level().dimension() != destination.dimension()) return entity.canUsePortal(true);
        return entity instanceof LivingEntity living ? living.isAlive() && !living.isSleeping() : entity.isAlive();
    }

    private static void teleport(ThrownEnderpearl pearl, Entity entity, ServerLevel impactWorld, Vec3 impact, boolean cooldown) {
        if (!valid(entity) || !eligible(entity, impactWorld)) return;
        final ServerLevel originWorld = (ServerLevel) entity.level();
        final Vec3 origin = entity.position();
        PearlTeleportFlow.attempt(new PearlTeleportFlow.Attempt<Location, Entity>() {
            @Override public Location eventDestination() {
                final Location to = new Location(impactWorld.getWorld(), impact.x, impact.y, impact.z, entity.getYRot(), entity.getXRot());
                if (!(entity instanceof ServerPlayer player)) return to;
                final PlayerTeleportEvent event = new PlayerTeleportEvent(player.getBukkitEntity(), player.getBukkitEntity().getLocation(), to, TeleportCause.ENDER_PEARL);
                return event.callEvent() ? event.getTo() : null;
            }

            @Override public boolean valid() {
                // Also catch a listener's already-completed same-region/world teleport.
                return PearlTeleport.valid(entity) && entity.level() == originWorld && entity.position().equals(origin);
            }

            @Override public void transfer(Location to, Consumer<Entity> completion) {
                if (!(to.getWorld() instanceof CraftWorld world)) return;
                to.checkFinite();
                final ServerLevel destination = world.getHandle();
                if (!eligible(entity, destination)) return;
                // No dismount, cooldown or fall-distance change until the event has accepted the move.
                if (entity.isPassenger()) entity.unRide();
                if (cooldown) entity.setPortalCooldown();
                entity.teleportAsync(destination, new Vec3(to.getX(), to.getY(), to.getZ()), to.getYaw(), to.getPitch(), null,
                    TeleportCause.ENDER_PEARL, Entity.TELEPORT_FLAG_TELEPORT_PASSENGERS | Entity.TELEPORT_FLAG_LOAD_CHUNK, completion);
            }

            @Override public void finish(Entity transferred) {
                complete(pearl, transferred);
            }
        });
    }

    private static void complete(ThrownEnderpearl pearl, Entity transferred) {
        if (!valid(transferred)) return;
        final ServerLevel world = (ServerLevel) transferred.level();
        final Vec3 position = transferred.position();
        if (transferred instanceof ServerPlayer player) {
            final float yaw = player.getYRot(), pitch = player.getXRot();
            PearlTeleportFlow.finish(world.getRandom().nextFloat(), world.isSpawningMonsters(),
                world.getLevelData().getDifficulty() == Difficulty.PEACEFUL,
                () -> {
                    final var endermite = EntityTypes.ENDERMITE.create(world, EntitySpawnReason.TRIGGERED);
                    if (endermite != null) {
                        endermite.snapTo(position.x, position.y, position.z, yaw, pitch);
                        world.addFreshEntity(endermite, CreatureSpawnEvent.SpawnReason.ENDER_PEARL);
                    }
                },
                () -> {
                    // A spawn listener may itself remove or transfer the player.
                    if (!valid(player) || player.level() != world || !player.position().equals(position)) return;
                    player.resetFallDistance();
                    player.resetCurrentImpulseContext();
                    player.hurtServer(world, player.damageSources().enderPearl().eventEntityDamager(pearl), 5.0F);
                },
                () -> sound(world, position));
        } else {
            transferred.resetFallDistance();
            sound(world, position);
        }
    }

    private static void sound(ServerLevel world, Vec3 position) {
        world.playSound(null, position.x, position.y, position.z, SoundEvents.PLAYER_TELEPORT, SoundSource.PLAYERS);
    }
}
