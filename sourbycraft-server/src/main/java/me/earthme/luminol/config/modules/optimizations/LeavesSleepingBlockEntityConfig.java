package me.earthme.luminol.config.modules.optimizations;

import me.earthme.luminol.config.IConfigModule;
import me.earthme.luminol.config.flags.ConfigClassInfo;
import me.earthme.luminol.config.flags.ConfigInfo;
import me.earthme.luminol.config.flags.HotReloadUnsupported;
import me.earthme.luminol.enums.EnumConfigCategory;

@ConfigClassInfo(category = EnumConfigCategory.OPTIMIZATIONS, name = "lithium_sleeping_block_entity")
public class LeavesSleepingBlockEntityConfig implements IConfigModule {
    // SourbyCraft: kept enabled as a SourbyCraft default (upstream Luminol default; documented here
    // as intentional). Pure-efficiency, behavior-neutral: a block entity with no pending work
    // "sleeps" (is skipped in the tick loop) and is woken the instant it has work again, so the
    // observable result is identical to ticking it every tick — it just avoids the no-op tick cost.
    // This is Lithium's replacement for Paper's removed hopper optimizations. Behavior parity is the
    // whole point of the Lithium implementation. Disable via optimizations.lithium_sleeping_block_entity.enabled=false.
    @ConfigInfo(name = "enabled", comments = """
            Use sleeping blocking optimizations from lithium,\s
             on luminol the hopper optimizations of paper were totally removed and replaced by those of lithium\s
            and it's turned on by default""")
    @HotReloadUnsupported
    public static boolean enabled = true;
}
