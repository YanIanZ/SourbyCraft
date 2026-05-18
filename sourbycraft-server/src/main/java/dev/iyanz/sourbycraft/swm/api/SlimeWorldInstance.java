package dev.iyanz.sourbycraft.swm.api;

import org.bukkit.World;
import org.jetbrains.annotations.Nullable;

public interface SlimeWorldInstance extends SlimeWorld {
    World getBukkitWorld();
    @Nullable SlimeWorld getSerializableCopy();
}
