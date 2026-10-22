package dev.iyanz.aurora.engine.world.block;

import net.minecraft.SharedConstants;
import net.minecraft.server.Bootstrap;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * OBS-14 engine world/block: {@link BlockTags#init} packs a few facts about a block state into one
 * int for the chunk code's fast paths. The flags must be independent single bits, and each bit must
 * agree with the vanilla fact it summarises.
 */
class BlockTagsTest {

    @BeforeAll
    static void bootstrapMinecraft() {
        SharedConstants.tryDetectVersion();
        Bootstrap.bootStrap();
    }

    private static boolean has(final int flags, final int bit) {
        return (flags & bit) == bit;
    }

    @Test
    void flagsAreDistinctSingleBitsAndFluidIsWaterOrLava() {
        final int[] bits = {BlockTags.RANDOMLY_TICKING, BlockTags.WATER, BlockTags.LAVA, BlockTags.CAN_HOLD_ANY_FLUID, BlockTags.INTERACTS_WITH_PRECIPITATION};
        int seen = 0;
        for (final int bit : bits) {
            assertEquals(1, Integer.bitCount(bit), "single bit: " + bit);
            assertEquals(0, seen & bit, "no two flags share a bit");
            seen |= bit;
        }
        assertEquals(BlockTags.WATER | BlockTags.LAVA, BlockTags.FLUID);
    }

    @Test
    void solidBlocksCarryNoFlag() {
        assertEquals(0, BlockTags.init(Blocks.STONE.defaultBlockState()));
        assertEquals(0, BlockTags.init(Blocks.OAK_PLANKS.defaultBlockState()));
    }

    // The WATER and LAVA bits come from FluidTags, which are not bound until a server loads its data packs,
    // so they cannot be asserted in a unit test: with only the registries bootstrapped `is(FluidTags.WATER)`
    // is false even for the water block. Everything else the summary holds does not depend on tags.

    @Test
    void liquidBlocksInteractWithPrecipitation() {
        assertTrue(has(BlockTags.init(Blocks.WATER.defaultBlockState()), BlockTags.INTERACTS_WITH_PRECIPITATION));
        assertTrue(has(BlockTags.init(Blocks.LAVA.defaultBlockState()), BlockTags.INTERACTS_WITH_PRECIPITATION));
    }

    @Test
    void cauldronsThatCatchRainOrSnowInteractWithPrecipitationAndTheLavaOneDoesNot() {
        for (final BlockState cauldron : new BlockState[] {
            Blocks.CAULDRON.defaultBlockState(), Blocks.WATER_CAULDRON.defaultBlockState(), Blocks.POWDER_SNOW_CAULDRON.defaultBlockState()
        }) {
            final int flags = BlockTags.init(cauldron);
            assertTrue(has(flags, BlockTags.INTERACTS_WITH_PRECIPITATION), cauldron.toString());
            assertEquals(0, flags & BlockTags.FLUID, "a cauldron block itself is not a fluid block: " + cauldron);
        }
        // LavaCauldronBlock is neither a CauldronBlock nor a LayeredCauldronBlock, and rain does not reach it
        assertFalse(has(BlockTags.init(Blocks.LAVA_CAULDRON.defaultBlockState()), BlockTags.INTERACTS_WITH_PRECIPITATION));
    }

    @Test
    void airCanHoldAnyFluid() {
        assertTrue(has(BlockTags.init(Blocks.AIR.defaultBlockState()), BlockTags.CAN_HOLD_ANY_FLUID));
        assertFalse(has(BlockTags.init(Blocks.STONE.defaultBlockState()), BlockTags.CAN_HOLD_ANY_FLUID));
    }

    @Test
    void randomlyTickingBlocksAreFlagged() {
        final BlockState grass = Blocks.GRASS_BLOCK.defaultBlockState();
        assertTrue(grass.isRandomlyTicking());
        assertTrue(has(BlockTags.init(grass), BlockTags.RANDOMLY_TICKING));
        assertFalse(has(BlockTags.init(Blocks.STONE.defaultBlockState()), BlockTags.RANDOMLY_TICKING));
    }

    @Test
    void theTagIndependentBitsAgreeWithVanillaForEveryBlockState() {
        // exhaustive over the registry's block states: the summary may never disagree with the vanilla fact it names
        net.minecraft.world.level.block.Block.BLOCK_STATE_REGISTRY.forEach(state -> {
            final int flags = BlockTags.init(state);
            assertEquals(state.isRandomlyTicking(), has(flags, BlockTags.RANDOMLY_TICKING), "random tick bit of " + state);
            assertEquals(net.minecraft.world.level.material.FlowingFluid.canHoldAnyFluid(state), has(flags, BlockTags.CAN_HOLD_ANY_FLUID), "hold fluid bit of " + state);
            final net.minecraft.world.level.block.Block block = state.getBlock();
            assertEquals(
                block instanceof net.minecraft.world.level.block.CauldronBlock
                    || block instanceof net.minecraft.world.level.block.LayeredCauldronBlock
                    || block instanceof net.minecraft.world.level.block.LiquidBlock,
                has(flags, BlockTags.INTERACTS_WITH_PRECIPITATION), "precipitation bit of " + state);
        });
    }
}
