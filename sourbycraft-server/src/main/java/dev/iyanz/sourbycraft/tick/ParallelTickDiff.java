package dev.iyanz.sourbycraft.tick;

import net.minecraft.world.entity.Entity;

public final class ParallelTickDiff {
    private final Entity entity;
    private final boolean needsInteract;

    private ParallelTickDiff(Entity entity, boolean needsInteract) {
        this.entity = entity;
        this.needsInteract = needsInteract;
    }

    public static ParallelTickDiff needsInteract(Entity entity) {
        return new ParallelTickDiff(entity, true);
    }

    public static ParallelTickDiff done(Entity entity) {
        return new ParallelTickDiff(entity, false);
    }

    public Entity entity() { return entity; }
    public boolean needsInteract() { return needsInteract; }
}
