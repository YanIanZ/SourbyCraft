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

    // SourbyCraft: runtime-tunable inactive-tick cadence. The Pufferfish base hardcoded a 1-in-20
    // cadence (tick the goal selector once every 20 inactive ticks). This field exposes that
    // interval so the SourbyCraft perf-engine can WIDEN it per-tier under load (tick even less
    // often => cheaper). Default 20 == the exact base behaviour, so at rest / GREEN there is ZERO
    // change. Only YELLOW+ tiers push a larger value via KnobEnforcer. A value <= 1 disables the
    // throttle (tick every inactive tick, i.e. vanilla). Not a @ConfigInfo key: it is driven by the
    // perf-engine, not the operator yml, and stays at the base default unless the engine moves it.
    public static volatile int inactiveTickInterval = 20;
}