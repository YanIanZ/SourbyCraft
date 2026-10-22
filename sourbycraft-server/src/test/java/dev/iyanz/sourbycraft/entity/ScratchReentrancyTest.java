package dev.iyanz.sourbycraft.entity;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.CALLS_REAL_METHODS;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.withSettings;

import java.io.InputStream;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.Set;
import java.util.TreeSet;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.phys.Vec3;
import org.junit.jupiter.api.Test;
import org.objectweb.asm.ClassReader;
import org.objectweb.asm.Opcodes;
import org.objectweb.asm.tree.AbstractInsnNode;
import org.objectweb.asm.tree.ClassNode;
import org.objectweb.asm.tree.FieldInsnNode;
import org.objectweb.asm.tree.InvokeDynamicInsnNode;
import org.objectweb.asm.tree.JumpInsnNode;
import org.objectweb.asm.tree.LabelNode;
import org.objectweb.asm.tree.LookupSwitchInsnNode;
import org.objectweb.asm.tree.MethodInsnNode;
import org.objectweb.asm.tree.MethodNode;
import org.objectweb.asm.tree.TableSwitchInsnNode;
import org.objectweb.asm.tree.TryCatchBlockNode;

/**
 * DEV-A: the entity-owned scalar scratch state the feature patches keep (0009's
 * {@code lastKnownSpeed*} doubles, 0012's {@code isInWallScratch}) against re-entry.
 * Evidence table: {@code docs/architecture/scratch-reentrancy-proof.md}.
 *
 * <p>What the bytecode tests are, honestly: <b>direct-call guards</b>. They pin the set of call
 * instructions in the method that holds the scratch live, so a call added there fails the test and
 * reopens the proof. They do not follow the callees: that a listed callee (say {@code position()})
 * acquires no callback is a separate, explicit check where one is made, and otherwise the manual
 * audit in the proof document. The override check covers the four subclasses named, not every
 * subclass. Publication (volatile) and control flow (every coordinate set on every path before a
 * read) are checked separately; the behavioural tests run the real methods on a bare
 * {@link Entity}.</p>
 */
@org.bukkit.support.environment.VanillaFeature
class ScratchReentrancyTest {

    private static ClassNode entityClass() throws Exception {
        try (InputStream in = Entity.class.getResourceAsStream("Entity.class")) {
            assertNotNull(in, "Entity.class bytes");
            final ClassNode node = new ClassNode();
            new ClassReader(in).accept(node, ClassReader.SKIP_DEBUG | ClassReader.SKIP_FRAMES);
            return node;
        }
    }

    private static MethodNode method(final ClassNode owner, final String name, final String desc) {
        for (final MethodNode method : owner.methods) {
            if (method.name.equals(name) && method.desc.equals(desc)) return method;
        }
        throw new AssertionError(owner.name + " has no " + name + desc);
    }

    /** Every call instruction in a method, as {@code owner.name desc}; an invokedynamic is reported too. */
    private static Set<String> calls(final MethodNode method) {
        final Set<String> calls = new TreeSet<>();
        for (final AbstractInsnNode insn : method.instructions) {
            if (insn instanceof MethodInsnNode call) calls.add(call.owner + "." + call.name + call.desc);
            if (insn instanceof InvokeDynamicInsnNode dyn) calls.add("invokedynamic " + dyn.name + dyn.desc);
        }
        return calls;
    }

    private static Field field(final String name) throws NoSuchFieldException {
        final Field field = Entity.class.getDeclaredField(name);
        field.setAccessible(true);
        return field;
    }

    private static Entity bareEntity(final Vec3 at) throws Exception {
        final Entity entity = mock(Entity.class, withSettings().defaultAnswer(CALLS_REAL_METHODS));
        field("position").set(entity, at);
        return entity;
    }

    private static void computeSpeed(final Entity entity) throws Exception {
        final Method computeSpeed = Entity.class.getDeclaredMethod("computeSpeed");
        computeSpeed.setAccessible(true);
        computeSpeed.invoke(entity);
    }

    // ---- 0009: Entity#lastKnownSpeedX/Y/Z ----------------------------------------------------------

    @Test
    void computeSpeedCannotBeReenteredBetweenItsStores() throws Exception {
        final ClassNode entity = entityClass();
        final Set<String> calls = calls(method(entity, "computeSpeed", "()V"));
        assertEquals(Set.of("net/minecraft/world/entity/Entity.position()Lnet/minecraft/world/phys/Vec3;",
                "java/lang/Math.sqrt(D)D"), calls,
            "direct calls: position() and Math.sqrt; neither can re-enter (position() checked below, sqrt is an intrinsic)");
        assertEquals(Set.of(), calls(method(entity, "position", "()Lnet/minecraft/world/phys/Vec3;")),
            "position() is a field read: no callback, no event, no listener");
        final List<String> overrides = new ArrayList<>();
        // No subclass may turn position() into something that calls out; Entity is the only declarer.
        for (final Class<?> type : List.of(net.minecraft.world.entity.LivingEntity.class, net.minecraft.world.entity.Mob.class,
            net.minecraft.server.level.ServerPlayer.class, net.minecraft.world.entity.vehicle.minecart.AbstractMinecart.class)) {
            try {
                type.getDeclaredMethod("position");
                overrides.add(type.getName());
            } catch (final NoSuchMethodException expected) {
                // not overridden
            }
        }
        assertEquals(List.of(), overrides);
    }

    /**
     * The reader another region's thread can reach must read ONE field, and that field must be
     * atomically published: two separately stored doubles can be observed as x of one tick and z of
     * another, and a plain double may itself tear (JLS 17.7). The Vec3 reference this replaced could
     * do neither. So: one volatile boolean, read once.
     */
    @Test
    void theCrossRegionReaderReadsASingleField() throws Exception {
        final MethodNode reader = method(entityClass(), "hasMovedHorizontallyRecently", "()Z");
        final List<String> fieldReads = new ArrayList<>();
        for (final AbstractInsnNode insn : reader.instructions) {
            if (insn instanceof FieldInsnNode field && insn.getOpcode() == Opcodes.GETFIELD) {
                fieldReads.add(field.name + ":" + field.desc);
            }
        }
        assertEquals(List.of("lastKnownMovedHorizontally:Z"), fieldReads, "exactly one boolean instance field, read once");
    }

    @Test
    void theCrossRegionPredicateIsVolatile() throws Exception {
        final Field published = field("lastKnownMovedHorizontally");
        assertEquals(boolean.class, published.getType());
        assertTrue(java.lang.reflect.Modifier.isVolatile(published.getModifiers()),
            "a cross-thread reader needs an atomic, visible publication");
        // The old derived double must be gone, not left behind as a second, plain publication.
        for (final Field declared : Entity.class.getDeclaredFields()) {
            assertFalse(declared.getName().equals("lastKnownSpeedHorizontalSq"), "stale plain-double publication still declared");
        }
    }

    @Test
    void theReadersOfTheSpeedScalarsOnlyReadFields() throws Exception {
        final ClassNode entity = entityClass();
        assertEquals(Set.of(), calls(method(entity, "hasMovedHorizontallyRecently", "()Z")), "a single field read, no call");
        final Set<String> known = calls(method(entity, "getKnownSpeed", "()Lnet/minecraft/world/phys/Vec3;"));
        assertTrue(known.contains("net/minecraft/world/entity/Entity.getControllingPassenger()Lnet/minecraft/world/entity/LivingEntity;"), known.toString());
        assertTrue(known.contains("net/minecraft/world/phys/Vec3.<init>(DDD)V"), known.toString());
        assertEquals(4, known.size(), "getControllingPassenger, isAlive, Player.getKnownSpeed, new Vec3: " + known);
    }

    @Test
    void speedScalarsMatchTheVec3Arithmetic() throws Exception {
        final Entity entity = bareEntity(new Vec3(1.0, 2.0, 3.0));
        computeSpeed(entity); // first call: lastKnownPosition == position, speed zero
        assertFalse(entity.hasMovedHorizontallyRecently());
        assertEquals(Vec3.ZERO, entity.getKnownSpeed());

        field("position").set(entity, new Vec3(1.5, 2.0, 2.0));
        computeSpeed(entity);
        assertEquals(new Vec3(0.5, 0.0, -1.0), entity.getKnownSpeed());
        assertTrue(entity.hasMovedHorizontallyRecently());

        field("position").set(entity, new Vec3(1.5, 9.0, 2.0)); // vertical only
        computeSpeed(entity);
        assertEquals(new Vec3(0.0, 7.0, 0.0), entity.getKnownSpeed());
        assertFalse(entity.hasMovedHorizontallyRecently());

        // Re-entering computeSpeed itself (the only re-entry its callers could ever produce) is
        // idempotent for a stationary entity and recomputes from the latest position otherwise.
        computeSpeed(entity);
        assertEquals(Vec3.ZERO, entity.getKnownSpeed());
    }

    /** Upstream's predicate on the same displacement: {@code Math.abs(speed.horizontalDistance()) > 1.0E-5F}. */
    private static boolean upstreamMoved(final double dx, final double dz) {
        return Math.abs(new Vec3(dx, 0.0, dz).horizontalDistance()) > 1.0E-5F;
    }

    private static boolean movedAfter(final double dx, final double dz) throws Exception {
        final Entity entity = bareEntity(new Vec3(0.0, 64.0, 0.0));
        computeSpeed(entity);
        field("position").set(entity, new Vec3(dx, 64.0, dz));
        computeSpeed(entity);
        return entity.hasMovedHorizontallyRecently();
    }

    @Test
    void theMovementThresholdIsUpstreamsFloatAtTheBoundary() throws Exception {
        final double floatThreshold = (double) 1.0E-5F;   // 9.999999747378752E-6
        assertTrue(floatThreshold < 1.0E-5D, "the float literal widens below 1e-5");
        // Exactly 1.0E-5: above the widened float threshold, so upstream says true. A double
        // threshold (0009/0044 before the fix) said false here.
        assertTrue(upstreamMoved(1.0E-5D, 0.0));
        assertTrue(movedAfter(1.0E-5D, 0.0), "displacement exactly 1.0E-5 counts as moved, as upstream");
        assertTrue(movedAfter(0.0, 1.0E-5D));
        // At the widened threshold itself: not strictly greater, false in both.
        assertFalse(upstreamMoved(floatThreshold, 0.0));
        assertFalse(movedAfter(floatThreshold, 0.0));
        assertTrue(movedAfter(Math.nextUp(floatThreshold), 0.0));
        assertFalse(movedAfter(Math.nextDown(floatThreshold), 0.0));
    }

    @Test
    void theMovementPredicateMatchesUpstreamOverManyDisplacements() throws Exception {
        final Random random = new Random(0xDE7AL);
        final double floatThreshold = (double) 1.0E-5F;
        for (int i = 0; i < 2_000; ++i) {
            // Mostly around the threshold, some far from it, both signs, both axes.
            final double scale = random.nextInt(4) == 0 ? 10.0 : floatThreshold * 1.5;
            final double dx = (random.nextDouble() * 2.0 - 1.0) * scale;
            final double dz = random.nextBoolean() ? 0.0 : (random.nextDouble() * 2.0 - 1.0) * scale;
            assertEquals(upstreamMoved(dx, dz), movedAfter(dx, dz), "dx=" + dx + " dz=" + dz);
        }
    }

    /**
     * The one reader another region's thread can reach — {@code hasMovedHorizontallyRecently},
     * through {@code FollowPlayerRiddenEntityGoal.canContinueToUse} — must never observe a mixed
     * tuple. The writer alternates between a speed of (a, 0, 0) and (0, 0, a): every consistent
     * state has horizontal speed a, so a {@code false} can only come from reading x from one
     * store and z from the other. A race test: it can only ever fail when a tear exists, and it
     * passed/failed as documented in the proof document.
     */
    @Test
    void aConcurrentReaderNeverSeesATornHorizontalSpeed() throws Exception {
        final Entity entity = bareEntity(new Vec3(0.0, 0.0, 0.0));
        computeSpeed(entity);                       // lastKnownPosition = origin, speed zero
        field("position").set(entity, new Vec3(1.0, 0.0, 0.0));
        computeSpeed(entity);                       // speed (1, 0, 0): every state from here on is "moved"
        assertTrue(entity.hasMovedHorizontallyRecently());
        final Vec3[] positions = {new Vec3(1.0, 0.0, 1.0), new Vec3(2.0, 0.0, 1.0), new Vec3(2.0, 0.0, 2.0), new Vec3(3.0, 0.0, 2.0)};
        final AtomicBoolean stop = new AtomicBoolean();
        final AtomicLong torn = new AtomicLong();
        final AtomicLong reads = new AtomicLong();
        final Thread reader = new Thread(() -> {
            long seen = 0;
            long bad = 0;
            while (!stop.get()) {
                if (!entity.hasMovedHorizontallyRecently()) bad++;
                seen++;
            }
            reads.set(seen);
            torn.set(bad);
        }, "ScratchReentrancyTest-reader");
        final Field position = field("position");
        reader.start();
        final long deadline = System.nanoTime() + 1_500_000_000L;
        int i = 0;
        try {
            while (System.nanoTime() < deadline) {
                position.set(entity, positions[i++ & 3]);
                computeSpeed(entity);
            }
        } finally {
            stop.set(true);
            reader.join(10_000);
        }
        assertTrue(reads.get() > 0, "the reader ran");
        assertEquals(0L, torn.get(), "torn (x from one tick, z from another) reads out of " + reads.get()
            + " after " + i + " writes");
    }

    // ---- 0012: Entity#isInWallScratch ---------------------------------------------------------------

    /**
     * {@code isInWall} iterates with the scratch position live. The calls it makes while the
     * scratch is live are block-state queries with no entity and no event: pinned here, so a call
     * added into the loop (an event, a plugin hook, anything that could tick an entity) fails this
     * test and reopens the proof.
     */
    @Test
    void isInWallCallsNothingThatCanReenterWhileItsScratchIsLive() throws Exception {
        final Set<String> calls = calls(method(entityClass(), "isInWall", "()Z"));
        final Set<String> allowed = Set.of(
            // the entity's own scalar getters (field reads) and the dimensions record
            "net/minecraft/world/entity/Entity.isSpectator()Z",
            "net/minecraft/world/entity/Entity.getX()D",
            "net/minecraft/world/entity/Entity.getEyeY()D",
            "net/minecraft/world/entity/Entity.getZ()D",
            "net/minecraft/world/entity/EntityDimensions.width()F",
            // scalar collision helpers
            "ca/spottedleaf/moonrise/patches/collisions/CollisionUtil.isEmpty(DDDDDD)Z",
            "ca/spottedleaf/moonrise/patches/collisions/CollisionUtil.voxelShapeIntersect(Lnet/minecraft/world/phys/AABB;Lnet/minecraft/world/phys/AABB;)Z",
            "ca/spottedleaf/moonrise/patches/collisions/CollisionUtil.voxelShapeIntersectNoEmpty(Lnet/minecraft/world/phys/shapes/VoxelShape;Lnet/minecraft/world/phys/AABB;)Z",
            "net/minecraft/util/Mth.floor(D)I",
            "ca/spottedleaf/moonrise/common/util/WorldUtil.getMinSection(Lnet/minecraft/world/level/Level;)I",
            // chunk/section lookups (reads of already-loaded data on the owning region)
            "net/minecraft/world/level/Level.getChunkSource()Lnet/minecraft/world/level/chunk/ChunkSource;",
            "net/minecraft/world/level/chunk/ChunkSource.getChunk(IILnet/minecraft/world/level/chunk/status/ChunkStatus;Z)Lnet/minecraft/world/level/chunk/ChunkAccess;",
            "net/minecraft/world/level/chunk/ChunkAccess.getSections()[Lnet/minecraft/world/level/chunk/LevelChunkSection;",
            "net/minecraft/world/level/chunk/LevelChunkSection.hasOnlyAir()Z",
            "net/minecraft/world/level/chunk/LevelChunkSection.getStates()Lnet/minecraft/world/level/chunk/PalettedContainer;",
            "net/minecraft/world/level/chunk/PalettedContainer.get(I)Ljava/lang/Object;",
            // the scratch position itself
            "net/minecraft/core/BlockPos$MutableBlockPos.setX(I)Lnet/minecraft/core/BlockPos$MutableBlockPos;",
            "net/minecraft/core/BlockPos$MutableBlockPos.setY(I)Lnet/minecraft/core/BlockPos$MutableBlockPos;",
            "net/minecraft/core/BlockPos$MutableBlockPos.setZ(I)Lnet/minecraft/core/BlockPos$MutableBlockPos;",
            // block-state queries: no entity argument, no CollisionContext, no event
            "ca/spottedleaf/moonrise/patches/collisions/block/CollisionBlockState.moonrise$emptyCollisionShape()Z",
            "net/minecraft/world/level/block/state/BlockState.isSuffocating(Lnet/minecraft/world/level/BlockGetter;Lnet/minecraft/core/BlockPos;)Z",
            "net/minecraft/world/level/block/state/BlockState.getCollisionShape(Lnet/minecraft/world/level/BlockGetter;Lnet/minecraft/core/BlockPos;)Lnet/minecraft/world/phys/shapes/VoxelShape;",
            "net/minecraft/world/phys/shapes/VoxelShape.isEmpty()Z",
            "ca/spottedleaf/moonrise/patches/collisions/shape/CollisionVoxelShape.moonrise$getSingleAABBRepresentation()Lnet/minecraft/world/phys/AABB;",
            "net/minecraft/world/phys/AABB.<init>(DDDDDD)V");
        final Set<String> unexpected = new TreeSet<>(calls);
        unexpected.removeAll(allowed);
        assertEquals(Set.of(), unexpected, "a call that is not a scalar getter, a section lookup or a block-state query");
    }

    /** The scratch reads in {@code isInWall}: the two block-state queries that take the scratch position. */
    private static boolean isScratchRead(final AbstractInsnNode insn) {
        return insn instanceof MethodInsnNode call
            && call.owner.equals("net/minecraft/world/level/block/state/BlockState")
            && (call.name.equals("isSuffocating") || call.name.equals("getCollisionShape"));
    }

    private static int setterBit(final AbstractInsnNode insn) {
        if (insn instanceof MethodInsnNode call && call.owner.equals("net/minecraft/core/BlockPos$MutableBlockPos")) {
            return switch (call.name) {
                case "setX" -> 1;
                case "setY" -> 2;
                case "setZ" -> 4;
                default -> 0;
            };
        }
        return 0;
    }

    /** Control-flow successors of every instruction, including exception edges. */
    private static Map<AbstractInsnNode, List<AbstractInsnNode>> successors(final MethodNode method) {
        final Map<AbstractInsnNode, List<AbstractInsnNode>> out = new IdentityHashMap<>();
        for (final AbstractInsnNode insn : method.instructions) {
            final List<AbstractInsnNode> next = new ArrayList<>();
            final int op = insn.getOpcode();
            final boolean fallsThrough = !(op == Opcodes.GOTO || (op >= Opcodes.IRETURN && op <= Opcodes.RETURN)
                || op == Opcodes.ATHROW || insn instanceof TableSwitchInsnNode || insn instanceof LookupSwitchInsnNode);
            if (fallsThrough && insn.getNext() != null) {
                next.add(insn.getNext());
            }
            if (insn instanceof JumpInsnNode jump) {
                next.add(jump.label);
            } else if (insn instanceof TableSwitchInsnNode table) {
                next.add(table.dflt);
                next.addAll(table.labels);
            } else if (insn instanceof LookupSwitchInsnNode lookup) {
                next.add(lookup.dflt);
                next.addAll(lookup.labels);
            }
            out.put(insn, next);
        }
        for (final TryCatchBlockNode block : method.tryCatchBlocks) {
            for (AbstractInsnNode insn = block.start; insn != null && insn != block.end; insn = insn.getNext()) {
                out.get(insn).add(block.handler);
            }
        }
        return out;
    }

    /**
     * Must-analysis over the real control-flow graph of {@code isInWall}: for each instruction, the
     * set of scratch coordinates (X, Y, Z) that every path from the method entry has set at least
     * once. Every scratch read must see all three. That is the R1 property: within one activation no
     * read can observe a coordinate left over from an earlier activation (or from another entity,
     * were the field ever shared). It is not a proof that each read sees the current iteration's
     * coordinates; that is the 0012 rewrite's functional correctness, covered by its own tests.
     */
    @Test
    void everyPathSetsAllThreeScratchCoordinatesBeforeEachRead() throws Exception {
        final MethodNode isInWall = method(entityClass(), "isInWall", "()Z");
        final Map<AbstractInsnNode, List<AbstractInsnNode>> successors = successors(isInWall);
        final Map<AbstractInsnNode, Integer> in = new IdentityHashMap<>();
        final ArrayDeque<AbstractInsnNode> work = new ArrayDeque<>();
        final AbstractInsnNode entry = isInWall.instructions.getFirst();
        in.put(entry, 0);
        work.add(entry);
        while (!work.isEmpty()) {
            final AbstractInsnNode insn = work.poll();
            final int out = in.get(insn) | setterBit(insn);
            for (final AbstractInsnNode next : successors.get(insn)) {
                final Integer previous = in.get(next);
                final int meet = previous == null ? out : previous & out;
                if (previous == null || meet != previous) {
                    in.put(next, meet);
                    work.add(next);
                }
            }
        }
        int reads = 0;
        final List<String> unset = new ArrayList<>();
        int index = 0;
        for (final AbstractInsnNode insn : isInWall.instructions) {
            if (isScratchRead(insn)) {
                reads++;
                final Integer state = in.get(insn);
                if (state != null && state != 7) {
                    unset.add("read #" + index + " sees only coordinate mask " + state);
                }
            }
            index++;
        }
        assertTrue(reads > 0, "isInWall still reads the scratch through a block-state query");
        assertEquals(List.of(), unset, "a path reaches a scratch read without setting every coordinate");
    }

    @Test
    void theMustAnalysisRejectsAPathThatSkipsASetter() {
        // Control for the analysis above: a hand-built method whose branch skips setZ must fail it.
        final MethodNode broken = new MethodNode(Opcodes.ACC_PUBLIC, "isInWall", "()Z", null, null);
        final LabelNode skip = new LabelNode();
        final String pos = "net/minecraft/core/BlockPos$MutableBlockPos";
        final String setterDesc = "(I)L" + pos + ";";
        broken.instructions.add(new MethodInsnNode(Opcodes.INVOKEVIRTUAL, pos, "setX", setterDesc));
        broken.instructions.add(new MethodInsnNode(Opcodes.INVOKEVIRTUAL, pos, "setY", setterDesc));
        broken.instructions.add(new JumpInsnNode(Opcodes.IFEQ, skip));
        broken.instructions.add(new MethodInsnNode(Opcodes.INVOKEVIRTUAL, pos, "setZ", setterDesc));
        broken.instructions.add(skip);
        broken.instructions.add(new MethodInsnNode(Opcodes.INVOKEVIRTUAL, "net/minecraft/world/level/block/state/BlockState",
            "isSuffocating", "(Lnet/minecraft/world/level/BlockGetter;Lnet/minecraft/core/BlockPos;)Z"));
        broken.instructions.add(new org.objectweb.asm.tree.InsnNode(Opcodes.IRETURN));
        final Map<AbstractInsnNode, List<AbstractInsnNode>> successors = successors(broken);
        final Map<AbstractInsnNode, Integer> in = new HashMap<>();
        final ArrayDeque<AbstractInsnNode> work = new ArrayDeque<>();
        in.put(broken.instructions.getFirst(), 0);
        work.add(broken.instructions.getFirst());
        while (!work.isEmpty()) {
            final AbstractInsnNode insn = work.poll();
            final int out = in.get(insn) | setterBit(insn);
            for (final AbstractInsnNode next : successors.get(insn)) {
                final Integer previous = in.get(next);
                final int meet = previous == null ? out : previous & out;
                if (previous == null || meet != previous) {
                    in.put(next, meet);
                    work.add(next);
                }
            }
        }
        AbstractInsnNode read = broken.instructions.getFirst();
        while (!isScratchRead(read)) {
            read = read.getNext();
        }
        assertEquals(3, in.get(read), "X and Y only: the skipping branch is seen");
    }

    @Test
    void theScratchPositionIsAnOwnerConfinedInstanceField() throws Exception {
        final Field scratch = field("isInWallScratch");
        assertTrue(java.lang.reflect.Modifier.isPrivate(scratch.getModifiers()));
        assertTrue(java.lang.reflect.Modifier.isFinal(scratch.getModifiers()));
        assertFalse(java.lang.reflect.Modifier.isStatic(scratch.getModifiers()));
    }
}
