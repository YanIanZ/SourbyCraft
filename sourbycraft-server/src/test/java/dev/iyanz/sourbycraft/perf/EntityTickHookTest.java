package dev.iyanz.sourbycraft.perf;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.fail;

import io.papermc.paper.threadedregions.RegionizedWorldData;
import java.io.IOException;
import java.io.InputStream;
import java.lang.reflect.Field;
import java.lang.reflect.Modifier;
import java.util.ArrayList;
import java.util.List;
import net.minecraft.server.level.ServerLevel;
import org.objectweb.asm.ClassReader;
import org.objectweb.asm.Opcodes;
import org.objectweb.asm.tree.AbstractInsnNode;
import org.objectweb.asm.tree.ClassNode;
import org.objectweb.asm.tree.FieldInsnNode;
import org.objectweb.asm.tree.InvokeDynamicInsnNode;
import org.objectweb.asm.tree.MethodInsnNode;
import org.objectweb.asm.tree.MethodNode;
import org.junit.jupiter.api.Test;

/**
 * Minecraft feature patch 0052: the region entity tick loop of {@code ServerLevel.tick} counts every
 * entity it passes to {@code guardEntityTick} in a plain field of the region's own
 * {@link RegionizedWorldData} and, after the loop, hands the count and the loop's duration to
 * {@link EntityTickCounters#regionTicked}. No world can be built in a unit test, so this checks the
 * compiled bytecode of the patched classes: the field shape, the order reset - loop - record in
 * {@code tick}, and the increment right before {@code guardEntityTick} inside the loop's lambda.
 */
class EntityTickHookTest {

    private static final String RWD = "io/papermc/paper/threadedregions/RegionizedWorldData";
    private static final String COUNTERS = "dev/iyanz/sourbycraft/perf/EntityTickCounters";
    private static final String FIELD = "sourbyEntityTicks";

    private static ClassNode serverLevel() throws IOException {
        try (InputStream in = ServerLevel.class.getResourceAsStream("ServerLevel.class")) {
            final ClassNode node = new ClassNode();
            new ClassReader(in).accept(node, ClassReader.SKIP_DEBUG | ClassReader.SKIP_FRAMES);
            return node;
        }
    }

    private static MethodNode method(final ClassNode owner, final String name, final String descPrefix) {
        return owner.methods.stream().filter(m -> m.name.equals(name) && m.desc.startsWith(descPrefix))
            .findFirst().orElseThrow(() -> new AssertionError("no method " + name + descPrefix));
    }

    private static int indexOf(final MethodNode method, final java.util.function.Predicate<AbstractInsnNode> p, final int from) {
        final AbstractInsnNode[] insns = method.instructions.toArray();
        for (int i = Math.max(0, from); i < insns.length; i++) {
            if (p.test(insns[i])) return i;
        }
        return -1;
    }

    private static boolean isField(final AbstractInsnNode insn, final int opcode) {
        return insn instanceof FieldInsnNode f && f.getOpcode() == opcode && f.owner.equals(RWD) && f.name.equals(FIELD);
    }

    private static boolean isCall(final AbstractInsnNode insn, final String owner, final String name) {
        return insn instanceof MethodInsnNode m && m.owner.equals(owner) && m.name.equals(name);
    }

    @Test
    void theRegionAccumulatorIsAPlainPublicIntField() throws NoSuchFieldException {
        final Field field = RegionizedWorldData.class.getField(FIELD);
        assertEquals(int.class, field.getType());
        final int mods = field.getModifiers();
        assertTrue(Modifier.isPublic(mods) && !Modifier.isStatic(mods), "public instance field");
        assertTrue(!Modifier.isVolatile(mods), "region-thread owned: a plain field, no fence per entity");
    }

    @Test
    void tickResetsTheCountRunsTheLoopThenRecordsOnce() throws IOException {
        final MethodNode tick = method(serverLevel(), "tick", "(Ljava/util/function/BooleanSupplier;");
        final int loop = indexOf(tick, i -> isCall(i, RWD, "forEachTickingEntity"), 0);
        assertTrue(loop >= 0, "entity loop present");
        final int reset = indexOf(tick, i -> isField(i, Opcodes.PUTFIELD), 0);
        assertTrue(reset >= 0 && reset < loop, "count reset before the loop");
        final int record = indexOf(tick, i -> isCall(i, COUNTERS, "regionTicked"), loop);
        assertTrue(record > loop, "recorded after the loop");
        final int read = indexOf(tick, i -> isField(i, Opcodes.GETFIELD), loop);
        assertTrue(read > loop && read < record, "the count read after the loop is what is recorded");
        assertEquals(-1, indexOf(tick, i -> isCall(i, COUNTERS, "regionTicked"), record + 1), "one record per loop");
    }

    @Test
    void theLoopLambdaCountsRightBeforeEachGuardedTick() throws IOException {
        final ClassNode level = serverLevel();
        final MethodNode tick = method(level, "tick", "(Ljava/util/function/BooleanSupplier;");
        final int loop = indexOf(tick, i -> isCall(i, RWD, "forEachTickingEntity"), 0);
        // the Consumer passed to forEachTickingEntity is the last invokedynamic before the call
        String lambdaName = null;
        final AbstractInsnNode[] insns = tick.instructions.toArray();
        for (int i = loop; i >= 0; i--) {
            if (insns[i] instanceof InvokeDynamicInsnNode indy) {
                lambdaName = ((org.objectweb.asm.Handle) indy.bsmArgs[1]).getName();
                break;
            }
        }
        if (lambdaName == null) fail("no lambda before forEachTickingEntity");
        final String name = lambdaName;
        final MethodNode lambda = level.methods.stream().filter(m -> m.name.equals(name)).findFirst().orElseThrow();

        final List<Integer> guards = new ArrayList<>();
        for (int i = indexOf(lambda, x -> isCall(x, "net/minecraft/server/level/ServerLevel", "guardEntityTick"), 0); i >= 0;
             i = indexOf(lambda, x -> isCall(x, "net/minecraft/server/level/ServerLevel", "guardEntityTick"), i + 1)) {
            guards.add(i);
        }
        assertEquals(1, guards.size(), "one guarded tick in the loop body");
        final int increment = indexOf(lambda, i -> isField(i, Opcodes.PUTFIELD), 0);
        assertTrue(increment >= 0 && increment < guards.get(0), "count stored before guardEntityTick");
        assertEquals(-1, indexOf(lambda, i -> isField(i, Opcodes.PUTFIELD), increment + 1), "counted once per entity");
        assertEquals(-1, indexOf(lambda, i -> isCall(i, COUNTERS, "regionTicked"), 0), "no recorder call per entity");
    }
}
