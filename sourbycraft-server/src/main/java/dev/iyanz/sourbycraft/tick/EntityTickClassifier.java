package dev.iyanz.sourbycraft.tick;

import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.ExperienceOrb;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.item.ItemEntity;
import net.minecraft.world.entity.projectile.arrow.AbstractArrow;

public final class EntityTickClassifier {
    private EntityTickClassifier() {}

    public static EntityClass classify(Entity e) {
        if (e == null) return EntityClass.OTHER;
        if (e instanceof ItemEntity) return EntityClass.ITEM;
        if (e instanceof ExperienceOrb) return EntityClass.EXP_ORB;
        if (e instanceof AbstractArrow) return EntityClass.ARROW;
        if (e instanceof LivingEntity) return EntityClass.LIVING;
        return EntityClass.OTHER;
    }
}
