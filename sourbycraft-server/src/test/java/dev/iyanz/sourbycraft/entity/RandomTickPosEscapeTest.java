package dev.iyanz.sourbycraft.entity;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotSame;

import java.util.function.UnaryOperator;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.world.ticks.ScheduledTick;
import net.minecraft.world.ticks.TickPriority;
import org.bukkit.craftbukkit.block.CraftBlock;
import org.junit.jupiter.api.Test;

/**
 * Regression contract of Minecraft feature patch 0010: {@code ServerLevel#optimiseRandomTick}
 * passes ONE {@code BlockPos.MutableBlockPos}, re-set per tick, to every {@code randomTick} of a
 * chunk instead of a fresh {@code BlockPos} per tick.
 *
 * <p>That is safe only while nothing a random tick reaches keeps the mutable: a kept reference would
 * silently move to the next ticked block. {@code docs/architecture/reuse-audit.md} names the capture
 * points that would matter and found that each copies; this test pins exactly those upstream
 * behaviours, so a Minecraft/Paper update that stops copying fails here before the patch is kept or
 * dropped. It does not prove that no block's {@code randomTick} stores {@code pos} itself (that is a
 * source audit, repeated per update); {@code ScratchBufferConfinementTest} pins that the scratch is
 * method-local, not a {@code ServerLevel} field.</p>
 */
class RandomTickPosEscapeTest {

    private static BlockPos.MutableBlockPos scratch() {
        return new BlockPos.MutableBlockPos(10, 64, -7);
    }

    @Test
    void aScheduledTickKeepsACopyNotTheScratch() {
        final BlockPos.MutableBlockPos pos = scratch();
        final ScheduledTick<String> tick = new ScheduledTick<>("block", pos, 5L, TickPriority.NORMAL, 0L);
        final ScheduledTick<String> shortForm = new ScheduledTick<>("block", pos, 5L, 0L);
        final ScheduledTick<String> probe = ScheduledTick.probe("block", pos);
        pos.set(99, 1, 99); // the next random tick re-sets the scratch
        for (final ScheduledTick<String> kept : java.util.List.of(tick, shortForm, probe)) {
            assertNotSame(pos, kept.pos());
            assertFalse(kept.pos() instanceof BlockPos.MutableBlockPos);
            assertEquals(new BlockPos(10, 64, -7), kept.pos());
        }
    }

    @Test
    void aCraftBlockKeepsACopyNotTheScratch() {
        // Every Bukkit block event raised from a random tick reaches the position through CraftBlock.at.
        final BlockPos.MutableBlockPos pos = scratch();
        final CraftBlock block = CraftBlock.at(null, pos);
        pos.set(99, 1, 99);
        assertFalse(block.getPosition() instanceof BlockPos.MutableBlockPos);
        assertEquals(new BlockPos(10, 64, -7), block.getPosition());
    }

    @Test
    void neighbourLookupsFromTheScratchAreImmutable() {
        // Random-tick code chains these on pos (pos.above(), pos.relative(dir), ...) and may keep the result.
        final java.util.Map<String, UnaryOperator<BlockPos>> chains = new java.util.LinkedHashMap<>();
        chains.put("above", BlockPos::above);
        chains.put("below", BlockPos::below);
        chains.put("above(2)", p -> p.above(2));
        chains.put("north", BlockPos::north);
        chains.put("south", BlockPos::south);
        chains.put("east", BlockPos::east);
        chains.put("west", BlockPos::west);
        chains.put("offset(1,2,3)", p -> p.offset(1, 2, 3));
        chains.put("relative(UP)", p -> p.relative(Direction.UP));
        chains.put("relative(EAST,4)", p -> p.relative(Direction.EAST, 4));
        chains.put("relative(Axis.Z,2)", p -> p.relative(Direction.Axis.Z, 2));
        chains.put("immutable", BlockPos::immutable);
        for (final var chain : chains.entrySet()) {
            final BlockPos.MutableBlockPos pos = scratch();
            final BlockPos expected = chain.getValue().apply(new BlockPos(10, 64, -7));
            final BlockPos derived = chain.getValue().apply(pos);
            pos.set(99, 1, 99);
            assertFalse(derived instanceof BlockPos.MutableBlockPos, chain.getKey() + " returned a mutable");
            assertEquals(expected, derived, chain.getKey());
        }
    }
}
