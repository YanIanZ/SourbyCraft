package dev.yanianz.intave.block.fluid;

import dev.yanianz.intave.block.variant.BlockVariantRegister;
import dev.yanianz.intave.integration.NativeFluidState;
import net.minecraft.world.level.block.state.BlockState;
import org.bukkit.Material;

/** Native SourbyCraft 26.2 adapter; the server already uses Mojang names. */
public final class v26FluidResolver implements FluidResolver {
    @Override public Fluid liquidFrom(Material type, int variantIndex) {
        Object raw = BlockVariantRegister.rawVariantOf(type, variantIndex);
        if (!(raw instanceof BlockState state))
            throw new IllegalStateException("Missing native block state for " + type + ":" + variantIndex);
        NativeFluidState fluid = NativeFluidState.from(state);
        return select(fluid.water(), fluid.lava(), fluid.dry(), fluid.source(),
                      fluid.falling(), fluid.height(), fluid.level());
    }
}
