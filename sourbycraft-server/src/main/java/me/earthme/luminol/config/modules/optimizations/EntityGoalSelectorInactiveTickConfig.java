package me.earthme.luminol.config.modules.optimizations;

import me.earthme.luminol.config.IConfigModule;
import me.earthme.luminol.config.flags.ConfigClassInfo;
import me.earthme.luminol.config.flags.ConfigInfo;
import me.earthme.luminol.enums.EnumConfigCategory;

@ConfigClassInfo(category = EnumConfigCategory.OPTIMIZATIONS, name = "throttle_goal_selector_tick_in_inactive_tick", comments =
        "Throttles the AI goal selector in entity inactive ticks. \n" +
                "This can improve performance by a few percent, but has minor gameplay implications."
)
public class EntityGoalSelectorInactiveTickConfig implements IConfigModule {
    // SourbyCraft: enabled by default. This Pufferfish optimization only throttles the AI goal
    // *selector* to 1-in-20 ticks while an entity is already in an inactive tick (out of the
    // activation range), where its behavior is negligible. Matches Pufferfish's own shipped
    // default and is a safe, few-percent CPU win for high-mob servers. Operators can disable it
    // via optimizations.throttle_goal_selector_tick_in_inactive_tick.enabled=false.
    @ConfigInfo(name = "enabled")
    public static boolean enabled = true;
}