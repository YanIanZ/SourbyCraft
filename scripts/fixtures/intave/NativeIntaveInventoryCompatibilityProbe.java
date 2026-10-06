import dev.yanianz.intave.test.MockEmptyInventory;
import dev.yanianz.intave.block.access.FakeFallbackBlock;
import net.minecraft.SharedConstants;
import net.minecraft.server.Bootstrap;
import net.minecraft.world.item.Items;
import org.bukkit.craftbukkit.inventory.CraftItemStack;
import org.bukkit.inventory.EquipmentSlot;
import org.bukkit.inventory.ItemStack;

/** Exercises the real private inventory against actual 26.2 item stacks; no server/world mocks. */
public final class NativeIntaveInventoryCompatibilityProbe {
    private static void check(boolean condition, String message) {
        if (!condition) throw new AssertionError(message);
    }

    private static ItemStack stone(int amount) {
        return CraftItemStack.asCraftMirror(new net.minecraft.world.item.ItemStack(Items.STONE, amount));
    }

    public static void main(String[] args) throws Exception {
        io.papermc.paper.configuration.GlobalConfiguration config = new io.papermc.paper.configuration.GlobalConfiguration();
        config.unsupportedSettings = config.new UnsupportedSettings();
        java.lang.reflect.Method set = config.getClass().getDeclaredMethod("set", config.getClass());
        set.setAccessible(true);
        set.invoke(null, config);
        SharedConstants.tryDetectVersion();
        Bootstrap.bootStrap();
        net.minecraft.core.registries.BuiltInRegistries.DATA_COMPONENT_INITIALIZERS
                .build(net.minecraft.data.registries.VanillaRegistries.createLookup())
                .forEach(net.minecraft.core.component.DataComponentInitializers.PendingComponents::apply);
        MockEmptyInventory inventory = new MockEmptyInventory();
        check(inventory.getSize() == 43, "26.2 inventory includes all equipment slots");
        check(inventory.isEmpty(), "new inventory is empty");
        inventory.setHeldItemSlot(8);
        EquipmentSlot[] equipment = EquipmentSlot.values();
        int[] indices = {8, 40, 36, 37, 38, 39, 41, 42};
        for (int i = 0; i < equipment.length; i++) {
            ItemStack stack = stone(i + 1);
            inventory.setItem(equipment[i], stack);
            check(inventory.getItem(indices[i]) == stack, "equipment and integer slots agree: " + equipment[i]);
            check(inventory.getItem(equipment[i]) == stack, "equipment read: " + equipment[i]);
        }
        check(!inventory.isEmpty(), "equipment counts towards emptiness");
        ItemStack[] contents = inventory.getContents();
        inventory.clear();
        inventory.setContents(contents);
        check(inventory.getItem(42).getAmount() == 8, "setContents preserves saddle slot");
        ItemStack[] detached = inventory.getExtraContents();
        detached[0] = null;
        check(inventory.getItem(40) != null, "extra array is detached");
        java.util.ListIterator<ItemStack> iterator = inventory.iterator(42);
        check(iterator.next().getAmount() == 8, "iterator covers extra slots");
        iterator.set(stone(9));
        check(inventory.getItem(42).getAmount() == 9, "iterator writes back");
        iterator.previous();
        check(iterator.previous().getAmount() == 7, "iterator traverses backwards");
        inventory.clear();
        inventory.setItem(0, stone(2));
        inventory.setItem(39, stone(3));
        inventory.setItem(42, stone(4));
        ItemStack request = stone(5);
        check(inventory.removeItem(request).get(0).getAmount() == 3, "storage removal leaves equipment alone");
        check(request.getAmount() == 5, "removal preserves caller request");
        check(inventory.getItem(39).getAmount() == 3, "storage removal preserves armor");
        java.util.HashMap<Integer, ItemStack> remaining = inventory.removeItemAnySlot(stone(10));
        check(remaining.get(0).getAmount() == 3, "all-slot removal reports actual remainder");
        check(inventory.isEmpty(), "all-slot removal clears armor and saddle");
        check(inventory.firstEmpty() == 0, "first empty searches storage");
        check(inventory.close() == 0 && inventory.getHolder(false) == null, "detached inventory has no viewers or owner");
        for (int index : new int[]{-1, 43}) {
            try { inventory.setItem(index, null); throw new AssertionError("invalid slot accepted"); }
            catch (ArrayIndexOutOfBoundsException expected) { }
        }
        ItemStack[] before = inventory.getContents();
        try { inventory.setContents(new ItemStack[44]); throw new AssertionError("oversized contents accepted"); }
        catch (IllegalArgumentException expected) { }
        check(java.util.Arrays.equals(before, inventory.getContents()), "invalid contents do not mutate inventory");
        inventory.setItem(0, stone(2));
        try { inventory.setContents(null); throw new AssertionError("null contents accepted"); }
        catch (NullPointerException expected) { }
        check(inventory.getItem(0).getAmount() == 2, "null contents preserve inventory");
        checkFallbackBlock();
        checkFluidStates();
        System.out.println("Native Intave 26.2 inventory compatibility probe passed");
    }

    private static void checkFallbackBlock() {
        FakeFallbackBlock block = new FakeFallbackBlock(null);
        check(block.getBlockData().getMaterial() == org.bukkit.Material.AIR, "fallback data is real AIR");
        check(block.getBlockData() != block.getBlockData(), "fallback block data is detached");
        check(block.isEmpty() && !block.isLiquid(), "fallback is dry AIR");
        check(!block.isBuildable() && !block.isBurnable() && block.isReplaceable(), "AIR flags");
        check(!block.isSolid() && !block.isCollidable() && !block.isSuffocating(), "AIR has no obstruction");
        check(block.isPassable() && block.getCollisionShape().getBoundingBoxes().isEmpty(), "AIR collision is empty");
        check(block.getBoundingBox().getVolume() == 0, "AIR has no bounding volume");
        check(block.getDrops(stone(1), null).isEmpty() && !block.isValidTool(stone(1)), "AIR has no drops");
        check(!block.breakNaturally(stone(1), true, true, true), "AIR cannot be broken");
        check(!block.applyBoneMeal(org.bukkit.block.BlockFace.UP), "AIR cannot grow");
        check(block.getPistonMoveReaction() == org.bukkit.block.PistonMoveReaction.MOVE, "AIR piston reaction");
        check(block.translationKey().equals("block.minecraft.air"), "real AIR translation");
        check(block.getBlockSoundGroup() != null, "real AIR sound group");
        check(block.rayTrace(new org.bukkit.Location(null, 0, 0, 0), new org.bukkit.util.Vector(1, 0, 0),
                10, org.bukkit.FluidCollisionMode.ALWAYS) == null, "AIR ray misses");
        check(block.getLocation(null) == null, "null location follows API contract");
        Runnable[] mutations = {block::tick, block::fluidTick, block::randomTick,
            () -> block.setBlockData(block.getBlockData()), () -> block.canPlace(block.getBlockData()),
            () -> block.getState(false), () -> block.getBreakSpeed(null)};
        for (Runnable mutation : mutations) {
            try { mutation.run(); throw new AssertionError("unsupported world access accepted"); }
            catch (UnsupportedOperationException expected) { }
        }
        FakeFallbackBlock overridden = new FakeFallbackBlock(null) {
            @Override public org.bukkit.Material getType() { return org.bukkit.Material.STONE; }
        };
        try { overridden.getBlockData(); throw new AssertionError("non-AIR subclass fabricated AIR state"); }
        catch (UnsupportedOperationException expected) { }
    }

    private static void checkFluidStates() {
        var dry = dev.yanianz.intave.integration.NativeFluidState.from(
                net.minecraft.world.level.block.Blocks.STONE.defaultBlockState());
        check(dry.dry() && !dry.source() && dry.height() == 0, "solid stone contains no fluid");
        for (boolean water : new boolean[]{true, false}) {
            var block = water ? net.minecraft.world.level.block.Blocks.WATER : net.minecraft.world.level.block.Blocks.LAVA;
            for (int level = 0; level <= 15; level++) {
                var state = block.defaultBlockState().setValue(net.minecraft.world.level.block.LiquidBlock.LEVEL, level);
                var fluid = dev.yanianz.intave.integration.NativeFluidState.from(state);
                check(!fluid.dry() && fluid.water() == water && fluid.lava() != water, "native fluid kind");
                check(fluid.source() == (level == 0) && fluid.level() == level, "native level/source at " + level);
                check(fluid.falling() == (level >= 8), "native falling at " + level);
                float height = (level == 0 || level >= 8 ? 8 : 8 - level) / 9.0f;
                check(Math.abs(fluid.height() - height) < 0.000001f, "native own height at " + level);
            }
        }
        var stairs = net.minecraft.world.level.block.Blocks.OAK_STAIRS.defaultBlockState()
                .setValue(net.minecraft.world.level.block.state.properties.BlockStateProperties.WATERLOGGED, true);
        var logged = dev.yanianz.intave.integration.NativeFluidState.from(stairs);
        check(logged.water() && logged.source() && !logged.falling() && logged.level() == 0,
                "waterlogged block uses normalized source water");
        try {
            dev.yanianz.intave.integration.NativeFluidState.from(null);
            throw new AssertionError("missing block state silently accepted");
        } catch (NullPointerException expected) { }
        checkMappedStates();
    }

    private static void checkMappedStates() {
        var blocks = new org.bukkit.Material[]{org.bukkit.Material.AIR, org.bukkit.Material.STONE,
                org.bukkit.Material.WATER, org.bukkit.Material.OAK_STAIRS, org.bukkit.Material.OAK_FENCE};
        for (var material : blocks) {
            var index = dev.yanianz.intave.integration.NativeBlockStates.index(material);
            var block = org.bukkit.craftbukkit.util.CraftMagicNumbers.getBlock(material);
            check(index.get(block.defaultBlockState()) == 0, "zero is the actual default: " + material);
            check(index.size() == block.getStateDefinition().getPossibleStates().size(), "every state indexed: " + material);
            check(new java.util.HashSet<>(index.values()).size() == index.size(), "IDs are unique: " + material);
            for (var state : block.getStateDefinition().getPossibleStates())
                check(index.containsKey(state), "registered state missing: " + state);
            check(index.equals(dev.yanianz.intave.integration.NativeBlockStates.index(material)), "index is repeatable");
        }
        try {
            dev.yanianz.intave.integration.NativeBlockStates.require(null);
            throw new AssertionError("missing native state accepted");
        } catch (IllegalStateException expected) { }
        try {
            dev.yanianz.intave.integration.NativeBlockStates.index(org.bukkit.Material.DIAMOND_SWORD);
            throw new AssertionError("non-block indexed");
        } catch (IllegalArgumentException expected) { }
        var block = net.minecraft.world.level.block.Blocks.OAK_STAIRS;
        var context = net.minecraft.world.level.EmptyBlockGetter.INSTANCE;
        var position = new net.minecraft.core.BlockPos(-31, 70, 33);
        for (var state : block.getStateDefinition().getPossibleStates()) {
            var shape = state.getCollisionShape(context, position);
            var boxes = dev.yanianz.intave.integration.NativeBlockStates.boxes(shape, -31, 70, 33);
            var original = shape.toAabbs();
            check(boxes.size() == original.size(), "complex shape retains every box");
            for (int i = 0; i < boxes.size(); i++)
                check(boxes.get(i).equals(original.get(i).move(-31, 70, 33)), "negative/positive geometry offsets");
        }
        check(dev.yanianz.intave.integration.NativeBlockStates.boxes(net.minecraft.world.phys.shapes.Shapes.empty(), 1, 2, 3).isEmpty(), "actual empty shape");
        try {
            var constructor = Class.forName("dev.yanianz.intave.block.variant.convert.v16ConversionBridge").getDeclaredConstructor();
            constructor.setAccessible(true);
            var bridge = (dev.yanianz.intave.block.variant.convert.ConversionBridge) constructor.newInstance();
            for (var state : block.getStateDefinition().getPossibleStates()) {
                var converted = bridge.settingsOf(state);
                check(converted.size() == state.getProperties().size(), "property count");
                for (var property : state.getProperties()) {
                    var actual = state.getValue(property);
                    Object expected = actual instanceof Enum<?> enumeration ? enumeration.name() : actual;
                    boolean found = converted.entrySet().stream().anyMatch(entry ->
                            entry.getKey().name().equals(property.getName()) && entry.getValue().equals(expected));
                    check(found, "mapped boolean/integer/enum property: " + property);
                }
            }
            for (int level = 0; level <= 15; level++) {
                var state = net.minecraft.world.level.block.Blocks.WATER.defaultBlockState().setValue(net.minecraft.world.level.block.LiquidBlock.LEVEL, level);
                int expected = level;
                check(bridge.settingsOf(state).entrySet().stream().anyMatch(entry -> entry.getKey().name().equals("level") && entry.getValue().equals(expected)), "integer property levels");
            }
        } catch (ReflectiveOperationException failure) { throw new AssertionError(failure); }
    }
}
