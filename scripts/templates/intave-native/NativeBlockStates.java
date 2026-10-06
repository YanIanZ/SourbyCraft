package dev.yanianz.intave.integration;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.shapes.VoxelShape;
import org.bukkit.Material;
import org.bukkit.craftbukkit.util.CraftMagicNumbers;

/** Immutable mapped state/geometry operations; never reads a world or invokes Patchy. */
public final class NativeBlockStates {
    private NativeBlockStates() { }

    public static BlockState defaultState(Material material) {
        if (!Objects.requireNonNull(material, "material").isBlock())
            throw new IllegalArgumentException("Not a block: " + material);
        Block block = Objects.requireNonNull(CraftMagicNumbers.getBlock(material), "native block for " + material);
        return block.defaultBlockState();
    }

    public static Map<Object, Integer> index(Material material) {
        BlockState defaultState = defaultState(material);
        Map<Object, Integer> index = new LinkedHashMap<>();
        // Zero means the actual default, including blocks whose default is not the first state.
        index.put(defaultState, 0);
        for (BlockState state : defaultState.getBlock().getStateDefinition().getPossibleStates())
            if (!index.containsKey(state)) index.put(state, index.size());
        return index;
    }

    public static BlockState require(Object raw) {
        if (!(raw instanceof BlockState state)) throw new IllegalStateException("Missing or non-native block state");
        return state;
    }

    public static List<AABB> boxes(VoxelShape shape, int x, int y, int z) {
        return Objects.requireNonNull(shape, "voxel shape").toAabbs().stream()
                .map(box -> box.move(x, y, z)).toList();
    }
}
