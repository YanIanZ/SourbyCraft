package dev.iyanz.aurora.engine.util.map;

import it.unimi.dsi.fastutil.objects.ObjectIterator;
import java.util.ArrayList;
import java.util.List;
import java.util.NoSuchElementException;
import java.util.Set;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.ai.behavior.Behavior;
import net.minecraft.world.entity.ai.behavior.BehaviorControl;
import net.minecraft.world.entity.ai.memory.MemoryModuleType;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * OBS-14 engine util/map: {@link BehaviorControlArraySet} is the brain's running-behaviour set. It
 * must behave as an insertion-ordered set (equality-based, no duplicates) and keep the backing
 * array null-terminated after removals so stale behaviours are not retained.
 *
 * <p>{@code ActivityArrayMap} and {@code ActivityBitSet} are not covered: their static
 * initialisers read {@code BuiltInRegistries.ACTIVITY}, which needs a bootstrapped registry.</p>
 */
class BehaviorControlArraySetTest {

    @Test
    void addIsEqualityBasedAndKeepsInsertionOrderAcrossGrowth() {
        final BehaviorControlArraySet<LivingEntity> set = new BehaviorControlArraySet<>();
        assertTrue(set.isEmpty());
        for (final String name : List.of("a", "b", "c", "d", "e")) {
            assertTrue(set.add(new Stub(name)));
        }

        assertFalse(set.add(new Stub("c")), "an equal behaviour is a duplicate");
        assertEquals(5, set.size());
        assertTrue(set.contains(new Stub("e")));
        assertFalse(set.contains(new Stub("z")));
        assertEquals(List.of("a", "b", "c", "d", "e"), names(set));
    }

    @Test
    void removeShiftsTheTailAndClearsTheVacatedSlot() {
        final BehaviorControlArraySet<LivingEntity> set = of("a", "b", "c", "d");

        assertTrue(set.remove(new Stub("b")));
        assertFalse(set.remove(new Stub("b")));
        assertEquals(List.of("a", "c", "d"), names(set));
        assertNull(set.raw()[3], "vacated slot must not retain the removed behaviour");

        assertTrue(set.remove(new Stub("d")));
        assertEquals(List.of("a", "c"), names(set));
        assertNull(set.raw()[2]);
    }

    @Test
    void iteratorRemoveAndSkip() {
        final BehaviorControlArraySet<LivingEntity> set = of("a", "b", "c", "d");

        final ObjectIterator<BehaviorControl<? super LivingEntity>> iterator = set.iterator();
        assertThrows(IllegalStateException.class, iterator::remove);
        assertEquals(new Stub("a"), iterator.next());
        assertEquals(new Stub("b"), iterator.next());
        iterator.remove();
        assertThrows(IllegalStateException.class, iterator::remove, "remove twice");
        assertEquals(1, iterator.skip(1));
        assertEquals(new Stub("d"), iterator.next());
        assertFalse(iterator.hasNext());
        assertThrows(NoSuchElementException.class, iterator::next);
        assertThrows(IllegalArgumentException.class, () -> set.iterator().skip(-1));
        assertEquals(0, iterator.skip(5));

        assertEquals(List.of("a", "c", "d"), names(set));
        assertNull(set.raw()[3]);
    }

    @Test
    void toArrayVariants() {
        final BehaviorControlArraySet<LivingEntity> set = of("a", "b");

        assertArrayEquals(new Object[] {new Stub("a"), new Stub("b")}, set.toArray());
        assertEquals(0, new BehaviorControlArraySet<LivingEntity>().toArray().length);

        final Object[] larger = {"x", "x", "x", "x"};
        assertSame(larger, set.toArray(larger));
        assertEquals(new Stub("a"), larger[0]);
        assertNull(larger[2], "element after the last is nulled");
        assertEquals("x", larger[3]);

        final Stub[] typed = set.toArray(new Stub[0]);
        assertEquals(2, typed.length);
    }

    @Test
    void runningCounterAndClear() {
        final BehaviorControlArraySet<LivingEntity> set = of("a", "b", "c");
        assertFalse(set.running());
        set.inc();
        set.inc();
        set.dec();
        assertTrue(set.running());
        set.dec();
        assertFalse(set.running());

        set.clear();
        assertTrue(set.isEmpty());
        for (final Object slot : set.raw()) assertNull(slot);
        assertTrue(set.add(new Stub("a")), "usable after clear");
    }

    private static BehaviorControlArraySet<LivingEntity> of(final String... names) {
        final BehaviorControlArraySet<LivingEntity> set = new BehaviorControlArraySet<>();
        for (final String name : names) set.add(new Stub(name));
        return set;
    }

    private static List<String> names(final BehaviorControlArraySet<LivingEntity> set) {
        final List<String> names = new ArrayList<>();
        for (final BehaviorControl<? super LivingEntity> behaviour : set) names.add(((Stub) behaviour).name());
        return names;
    }

    /** Value-equal behaviour stub; none of its game methods are called by the set. */
    private record Stub(String name) implements BehaviorControl<LivingEntity> {
        @Override
        public Behavior.Status getStatus() {
            throw new UnsupportedOperationException();
        }

        @Override
        public Set<MemoryModuleType<?>> getRequiredMemories() {
            throw new UnsupportedOperationException();
        }

        @Override
        public boolean tryStart(final ServerLevel level, final LivingEntity body, final long timestamp) {
            throw new UnsupportedOperationException();
        }

        @Override
        public void tickOrStop(final ServerLevel level, final LivingEntity body, final long timestamp) {
            throw new UnsupportedOperationException();
        }

        @Override
        public void doStop(final ServerLevel level, final LivingEntity body, final long timestamp) {
            throw new UnsupportedOperationException();
        }

        @Override
        public String debugString() {
            return name;
        }
    }
}
