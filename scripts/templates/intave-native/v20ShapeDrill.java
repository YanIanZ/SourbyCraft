package dev.yanianz.intave.block.shape.resolve.drill;

import dev.yanianz.intave.block.shape.BlockShape;
import dev.yanianz.intave.block.shape.BlockShapes;
import dev.yanianz.intave.block.variant.BlockVariantRegister;
import dev.yanianz.intave.integration.NativeBlockStates;
import dev.yanianz.intave.integration.NativeBlockView;
import dev.yanianz.intave.share.BoundingBox;
import net.minecraft.core.BlockPos;
import org.bukkit.Material;
import org.bukkit.World;
import org.bukkit.entity.Player;

/** Mapped geometry; neighbor lookups pass through the same owner-checked non-loading view. */
public final class v20ShapeDrill extends AbstractShapeDrill {
    private BlockShape shape(World world, Material type, int variant, int x, int y, int z, boolean collision) {
        NativeBlockView view = new NativeBlockView(world);
        BlockPos pos = new BlockPos(x, y, z);
        view.getBlockState(pos);
        var state = NativeBlockStates.require(BlockVariantRegister.rawVariantOf(type, variant));
        var shape = collision ? state.getCollisionShape(view, pos) : state.getShape(view, pos);
        var boxes = NativeBlockStates.boxes(shape, x, y, z).stream()
                .map(box -> new BoundingBox(box.minX, box.minY, box.minZ, box.maxX, box.maxY, box.maxZ)).toList();
        return BlockShapes.fromElementaryBoxes(boxes);
    }
    @Override public BlockShape collisionShapeOf(World world, Player player, Material type, int variant, int x, int y, int z) {
        return shape(world, type, variant, x, y, z, true);
    }
    @Override public BlockShape outlineShapeOf(World world, Player player, Material type, int variant, int x, int y, int z) {
        return shape(world, type, variant, x, y, z, false);
    }
}
