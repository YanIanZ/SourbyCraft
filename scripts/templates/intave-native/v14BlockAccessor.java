package dev.yanianz.intave.block.access;

import com.comphenix.protocol.wrappers.BlockPosition;
import org.bukkit.Material;
import org.bukkit.World;
import org.bukkit.block.Block;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;

/** Historical internal name retained; the private server supports only native 26.2. */
public final class v14BlockAccessor implements BlockAccessor {
    private final v20BlockAccessor nativeAccess = new v20BlockAccessor();
    @Override public Material typeOf(Block block) { return nativeAccess.typeOf(block); }
    @Override public int variantIndexOf(Block block) { return nativeAccess.variantIndexOf(block); }
    @Override public Object nativeVariantOf(Block block) { return nativeAccess.nativeVariantOf(block); }
    @Override public Object nativeVariantBy(int id) { return nativeAccess.nativeVariantBy(id); }
    @Override public float blockDamage(World world, Player player, ItemStack item, BlockPosition pos) {
        return nativeAccess.blockDamage(world, player, item, pos);
    }
    @Override public boolean replacementPlace(World world, Player player, BlockPosition pos) {
        return nativeAccess.replacementPlace(world, player, pos);
    }
}
