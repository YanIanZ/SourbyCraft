package dev.yanianz.intave.block.access;

import ca.spottedleaf.moonrise.common.util.TickThread;
import com.comphenix.protocol.wrappers.BlockPosition;
import dev.yanianz.intave.block.variant.BlockVariantRegister;
import dev.yanianz.intave.integration.NativeBlockStates;
import dev.yanianz.intave.integration.NativeBlockView;
import dev.yanianz.intave.user.User;
import dev.yanianz.intave.user.UserRepository;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.level.block.state.BlockState;
import org.bukkit.Material;
import org.bukkit.World;
import org.bukkit.block.Block;
import org.bukkit.craftbukkit.entity.CraftPlayer;
import org.bukkit.craftbukkit.util.CraftMagicNumbers;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;

/** Native 26.2 adapter. Block reads are racy reads of loaded chunks (unloaded = AIR); player access stays owner-checked. */
public final class v20BlockAccessor implements BlockAccessor {
    private BlockState state(Block block) {
        return new NativeBlockView(block.getWorld()).getBlockState(new BlockPos(block.getX(), block.getY(), block.getZ()));
    }
    @Override public Material typeOf(Block block) { return CraftMagicNumbers.getMaterial(state(block).getBlock()); }
    @Override public Object nativeVariantOf(Block block) { return state(block); }
    @Override public int variantIndexOf(Block block) {
        BlockState state = state(block);
        int id = BlockVariantRegister.variantIndexOf(CraftMagicNumbers.getMaterial(state.getBlock()), state);
        if (id < 0) throw new IllegalStateException("Unindexed native block state: " + state);
        return id;
    }
    @Override public Object nativeVariantBy(int id) {
        throw new UnsupportedOperationException("Legacy numeric block IDs are unavailable on native 26.2");
    }
    private ServerPlayer owner(World world, Player player) {
        ServerPlayer handle = ((CraftPlayer) player).getHandle();
        TickThread.ensureTickThread(handle, "Native Intave inventory read outside player owner region");
        if (!player.getWorld().equals(world)) throw new IllegalArgumentException("Player and block worlds differ");
        return handle;
    }
    private BlockState simulatedState(World world, Player player, BlockPosition pos) {
        User user = UserRepository.userOf(player);
        var location = pos.toLocation(world);
        return NativeBlockStates.require(BlockVariantRegister.rawVariantOf(
                VolatileBlockAccess.typeAccess(user, location), VolatileBlockAccess.variantIndexAccess(user, location)));
    }
    @Override public float blockDamage(World world, Player player, ItemStack itemInHand, BlockPosition pos) {
        ServerPlayer handle = owner(world, player);
        BlockPos target = new BlockPos(pos.getX(), pos.getY(), pos.getZ());
        NativeBlockView view = new NativeBlockView(world);
        return simulatedState(world, player, pos).getDestroyProgress(handle, view, target);
    }
    @Override public boolean replacementPlace(World world, Player player, BlockPosition pos) {
        ServerPlayer handle = owner(world, player);
        User user = UserRepository.userOf(player);
        BlockState state = simulatedState(world, player, pos);
        return state.canBeReplaced() && state.getBlock().asItem() != handle.getInventory().getItem(user.meta().inventory().handSlot()).getItem();
    }
}
