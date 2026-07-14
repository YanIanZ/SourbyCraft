package me.earthme.luminol.config.modules.optimizations;

import me.earthme.luminol.config.IConfigModule;
import me.earthme.luminol.config.flags.ConfigClassInfo;
import me.earthme.luminol.config.flags.ConfigInfo;
import me.earthme.luminol.enums.EnumConfigCategory;

@ConfigClassInfo(category = EnumConfigCategory.OPTIMIZATIONS, name = "reduce_sensor_work", comments = "When it is enabled, it will delete the line of sight cache less often and use a faster nearby comparison.")
public class PetalReduceSensorWorkConfig implements IConfigModule {
    // SourbyCraft: kept enabled as a SourbyCraft default (upstream Luminol default; documented here
    // as intentional so an upstream sync cannot silently revert it). Pure-efficiency, behavior-neutral:
    // the entity sensor still detects the same targets — this only reuses the per-entity line-of-sight
    // cache for `delay_ticks` ticks instead of recomputing it every tick, and swaps an equivalent but
    // faster nearby-entity comparison. No observable AI/gameplay change; a few-percent CPU win on
    // high-mob servers. Disable via optimizations.reduce_sensor_work.enabled=false.
    @ConfigInfo(name = "enabled")
    public static boolean enabled = true;
    @ConfigInfo(name = "delay_ticks", comments = "The interval of each entity to drop the cache(in ticks)")
    public static int delayTicks = 10;
}