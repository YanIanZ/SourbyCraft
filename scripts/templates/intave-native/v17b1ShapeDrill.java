package dev.yanianz.intave.block.shape.resolve.drill;

import dev.yanianz.intave.block.shape.BlockShape;
import org.bukkit.Material;
import org.bukkit.World;
import org.bukkit.entity.Player;

/** Historical internal name retained; the private server supports only native 26.2. */
public final class v17b1ShapeDrill extends AbstractShapeDrill {
    private final v20ShapeDrill nativeDrill = new v20ShapeDrill();
    @Override public BlockShape collisionShapeOf(World world, Player player, Material type, int variant, int x, int y, int z) {
        return nativeDrill.collisionShapeOf(world, player, type, variant, x, y, z);
    }
    @Override public BlockShape outlineShapeOf(World world, Player player, Material type, int variant, int x, int y, int z) {
        return nativeDrill.outlineShapeOf(world, player, type, variant, x, y, z);
    }
}
