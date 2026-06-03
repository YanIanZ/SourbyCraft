# NMS particle emit sites — fall + death (Paper 1.21.11 mojmap)

Inputs for patch `0034-us-particles-fall-death.patch`. Captured after the upstream
paperweight cache populated paper-server sources on commit `a9c6ec4`.

Source root: `.gradle/caches/paperweight/upstreams/server-work/paper/src/minecraft/java/`

## Fall particles

File: `net/minecraft/world/entity/LivingEntity.java`
Method: `protected void checkFallDamage(double y, boolean onGround, BlockState state, BlockPos pos)`
Lines: 400-405

Vanilla snippet (lines 396-406):

```java
                double d1 = Math.min(0.2F + d / 15.0, 2.5);
                int i = (int)(150.0 * d1);
                // CraftBukkit start - visibility api
                if (this instanceof ServerPlayer) {
                    serverLevel.sendParticlesSource((ServerPlayer) this, new BlockParticleOption(ParticleTypes.BLOCK, state), false, false, x, y1, z, i, 0.0, 0.0, 0.0, 0.15F);
                } else {
                    serverLevel.sendParticles(new BlockParticleOption(ParticleTypes.BLOCK, state), x, y1, z, i, 0.0, 0.0, 0.0, 0.15F);
                }
                // CraftBukkit end
            }
```

Insertion strategy: wrap the entire `if (this instanceof ServerPlayer) { ... } else { ... }` block with a `ymlBool` guard. Both branches are particle emit sites; gating the outer if/else preserves the CraftBukkit visibility-api split.

## Death particles

File: `net/minecraft/world/entity/LivingEntity.java`
Method: `protected void tickDeath()`
Line: 589

Vanilla snippet (lines 586-592):

```java
    protected void tickDeath() {
        this.deathTime++;
        if (this.deathTime >= 20 && !this.level().isClientSide() && !this.isRemoved()) {
            this.level().broadcastEntityEvent(this, EntityEvent.POOF);
            this.remove(Entity.RemovalReason.KILLED, org.bukkit.event.entity.EntityRemoveEvent.Cause.DEATH); // CraftBukkit - add Bukkit remove cause
        }
    }
```

Insertion strategy: wrap **only** the `broadcastEntityEvent(this, EntityEvent.POOF)` line. The `remove(...)` call must continue to fire unconditionally — gating it would break entity removal entirely. This is the single emit site for both mobs and players because `Mob.java` inherits `tickDeath` from `LivingEntity` (verified by `grep tickDeath net/minecraft/world/entity/Mob.java` returning no override).

## Notes

- Both sites are called in non-loop contexts (one per fall-damage event, one per death tick). No bool-caching is required per the patch template's hot-loop rule.
- `EntityEvent.POOF` is the death-poof particle event byte; the client receives it via `ClientboundEntityEventPacket` and renders the puff-of-smoke + sound.
- `serverLevel.sendParticles` / `sendParticlesSource` directly produce `ClientboundLevelParticlesPacket`. Gating these means zero packets emitted for the fall site.
- `SourbyCraftConfig` is in package `dev.iyanz.sourbycraft`. An import statement is required at the top of `LivingEntity.java` (or use the fully-qualified name inline).
