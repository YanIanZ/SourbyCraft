package dev.yanianz.intave.integration;

import java.util.Objects;
import net.minecraft.world.level.block.LiquidBlock;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.material.FlowingFluid;
import net.minecraft.world.level.material.FluidState;
import net.minecraft.world.level.material.Fluids;

/** Reads immutable 26.2 block states without world access, reflection, or runtime remapping. */
public record NativeFluidState(boolean dry, boolean water, boolean lava, boolean source,
                               boolean falling, float height, int level) {
    public static NativeFluidState from(BlockState block) {
        FluidState fluid = Objects.requireNonNull(block, "block state").getFluidState();
        if (fluid.isEmpty()) return new NativeFluidState(true, false, false, false, false, 0, 0);
        boolean water = fluid.getType().isSame(Fluids.WATER);
        boolean lava = fluid.getType().isSame(Fluids.LAVA);
        if (!water && !lava) throw new IllegalStateException("Unsupported nonempty native fluid: " + fluid);
        boolean source = fluid.isSource();
        boolean falling = fluid.hasProperty(FlowingFluid.FALLING) && fluid.getValue(FlowingFluid.FALLING);
        // Liquid block level is the existing serialized simulation representation;
        // waterlogged blocks use the same source normalization as the upstream resolver.
        int level = block.hasProperty(LiquidBlock.LEVEL) ? block.getValue(LiquidBlock.LEVEL) : 8;
        return new NativeFluidState(false, water, lava, source, source ? false : falling,
                                    fluid.getOwnHeight(), source ? 0 : level);
    }
}
