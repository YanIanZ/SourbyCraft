package dev.iyanz.aurora.engine.util.map;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.NoSuchElementException;
import java.util.Set;
import net.minecraft.SharedConstants;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.server.Bootstrap;
import net.minecraft.world.entity.schedule.Activity;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * OBS-14 engine util/map: {@link ActivityBitSet}, the set of activities a brain has switched on. One
 * int holds the whole set, and a dirty flag tells the brain when to rebuild its derived state.
 * Like {@link ActivityArrayMap} it is single-owner (not thread safe) by design.
 */
class ActivityBitSetTest {

    @BeforeAll
    static void bootstrapMinecraft() {
        SharedConstants.tryDetectVersion();
        Bootstrap.bootStrap();
    }

    private static int mask(final Activity... activities) {
        int mask = 0;
        for (final Activity activity : activities) {
            mask |= 1 << activity.id;
        }
        return mask;
    }

    private static List<Activity> contents(final ActivityBitSet set) {
        final List<Activity> out = new ArrayList<>();
        set.iterator().forEachRemaining(out::add);
        return out;
    }

    @Test
    void sizeConstantIsTheRegistrySizeAndFitsTheMask() {
        assertEquals(BuiltInRegistries.ACTIVITY.size(), ActivityBitSet.ACTIVITY_SIZE);
        assertTrue(ActivityBitSet.ACTIVITY_SIZE <= Integer.SIZE, "an int mask cannot hold " + ActivityBitSet.ACTIVITY_SIZE + " activities");
    }

    @Test
    void startsEmptyAndDirty() {
        final ActivityBitSet set = new ActivityBitSet();
        assertTrue(set.isEmpty());
        assertEquals(0, set.size());
        assertEquals(0, set.bitSet());
        assertFalse(set.iterator().hasNext());
        assertTrue(set.unsetDirty(), "a new set must be rebuilt once");
        assertFalse(set.unsetDirty(), "and only once");
    }

    @Test
    void addReportsNovelty() {
        final ActivityBitSet set = new ActivityBitSet();
        assertTrue(set.add(Activity.WORK));
        assertFalse(set.add(Activity.WORK));
        assertTrue(set.add(Activity.CORE));

        assertEquals(2, set.size());
        assertTrue(set.contains(Activity.WORK));
        assertTrue(set.contains(Activity.CORE));
        assertFalse(set.contains(Activity.IDLE));
        assertEquals(mask(Activity.WORK, Activity.CORE), set.bitSet());
    }

    @Test
    void removeReportsPresenceAndIgnoresForeignObjects() {
        final ActivityBitSet set = new ActivityBitSet();
        set.add(Activity.WORK);

        assertFalse(set.remove(Activity.IDLE));
        assertFalse(set.remove("work"));
        assertFalse(set.remove(null));
        assertTrue(set.remove(Activity.WORK));
        assertFalse(set.remove(Activity.WORK));
        assertTrue(set.isEmpty());
        assertEquals(0, set.bitSet());
    }

    @Test
    void containsIgnoresForeignObjects() {
        final ActivityBitSet set = new ActivityBitSet();
        set.add(Activity.WORK);
        assertFalse(set.contains("work"));
        assertFalse(set.contains(null));
        assertFalse(set.contains(Integer.valueOf(Activity.WORK.id)));
    }

    @Test
    void dirtyFlagTracksOnlyRealChanges() {
        final ActivityBitSet set = new ActivityBitSet();
        set.unsetDirty();

        set.add(Activity.WORK);
        assertTrue(set.unsetDirty(), "add of a new element");

        set.add(Activity.WORK);
        assertFalse(set.unsetDirty(), "add of a present element");

        set.remove(Activity.IDLE);
        assertFalse(set.unsetDirty(), "remove of an absent element");

        set.remove(Activity.WORK);
        assertTrue(set.unsetDirty(), "remove of a present element");

        set.clear();
        assertTrue(set.unsetDirty(), "clear always marks dirty, even when already empty");
    }

    @Test
    void iterationIsInAscendingIdOrderWhateverTheInsertionOrder() {
        final ActivityBitSet set = new ActivityBitSet();
        final List<Activity> all = new ArrayList<>();
        BuiltInRegistries.ACTIVITY.forEach(all::add);
        for (int i = all.size() - 1; i >= 0; i--) {
            set.add(all.get(i));
        }

        assertEquals(all.size(), set.size());
        assertEquals(all, contents(set));
        assertEquals(all.size() == 32 ? -1 : (1 << all.size()) - 1, set.bitSet(), "registry ids are dense from 0");
    }

    @Test
    void iterationSkipsGapsAndCoversTheLastBit() {
        final ActivityBitSet set = new ActivityBitSet();
        final Activity last = BuiltInRegistries.ACTIVITY.byIdOrThrow(ActivityBitSet.ACTIVITY_SIZE - 1);
        set.add(last);
        set.add(Activity.PLAY);

        assertEquals(List.of(Activity.PLAY, last), contents(set));
        final var it = set.iterator();
        it.next();
        it.next();
        assertFalse(it.hasNext());
        assertThrows(NoSuchElementException.class, it::next);
    }

    @Test
    void clearEmptiesTheSet() {
        final ActivityBitSet set = new ActivityBitSet();
        set.add(Activity.WORK);
        set.add(Activity.REST);
        set.clear();

        assertTrue(set.isEmpty());
        assertEquals(0, set.bitSet());
        assertFalse(set.contains(Activity.WORK));
        assertFalse(set.iterator().hasNext());
    }

    @Test
    void equalsAndHashCodeFollowTheSetContractAgainstAHashSet() {
        final ActivityBitSet set = new ActivityBitSet();
        set.add(Activity.WORK);
        set.add(Activity.REST);
        final Set<Activity> reference = new HashSet<>(Set.of(Activity.REST, Activity.WORK));

        assertEquals(reference, set);
        assertEquals(set, reference);
        assertEquals(reference.hashCode(), set.hashCode());

        reference.add(Activity.CORE);
        assertNotEquals(set, reference, "different size");
        reference.remove(Activity.REST);
        assertNotEquals(set, reference, "same size, different member");
        assertNotEquals(set, "not a set");
        assertEquals(set, set);

        final ActivityBitSet other = new ActivityBitSet();
        other.add(Activity.REST);
        other.add(Activity.WORK);
        assertEquals(set, other);
        assertEquals(set.hashCode(), other.hashCode());
    }

    @Test
    void emptySetsAreEqualToEachOtherAndToAnEmptyJavaSet() {
        assertEquals(new ActivityBitSet(), new ActivityBitSet());
        assertEquals(Set.of(), new ActivityBitSet());
        assertEquals(0, new ActivityBitSet().hashCode());
    }
}
