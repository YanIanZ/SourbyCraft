package dev.iyanz.sourbycraft.tick;

import net.minecraft.world.entity.Entity;

public final class ParallelTickSnapshot {
    private final Entity entity;
    private final EntityClass entityClass;

    public ParallelTickSnapshot(Entity entity, EntityClass entityClass) {
        this.entity = entity;
        this.entityClass = entityClass;
    }

    public Entity entity() { return entity; }
    public EntityClass entityClass() { return entityClass; }
}
