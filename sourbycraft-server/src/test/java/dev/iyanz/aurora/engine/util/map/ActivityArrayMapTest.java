package dev.iyanz.aurora.engine.util.map;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.NoSuchElementException;
import net.minecraft.SharedConstants;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.server.Bootstrap;
import net.minecraft.world.entity.schedule.Activity;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * OBS-14 engine util/map: {@link ActivityArrayMap}, the brain's activity to behaviours table.
 *
 * <p>It is an insertion-ordered array map keyed by {@link Activity#id}. It is <b>not</b> a concurrent
 * map: nothing in it is volatile or locked, because a brain is only ever touched by the thread that
 * owns its entity. So "two writers" can only mean two writers taking turns; the test for that
 * hands the map from one thread to the next through {@code Thread.join} (a happens-before edge) and
 * does not claim any safety for simultaneous writers.</p>
 */
class ActivityArrayMapTest {

    @BeforeAll
    static void bootstrapMinecraft() {
        SharedConstants.tryDetectVersion();
        Bootstrap.bootStrap();
    }

    private static ActivityArrayMap<String> map() {
        return new ActivityArrayMap<>(new String[0]);
    }

    private static List<Activity> allActivities() {
        final List<Activity> all = new ArrayList<>();
        BuiltInRegistries.ACTIVITY.forEach(all::add);
        return all;
    }

    @Test
    void registryIdsAreTheActivityIdsTheMapAndTheBitSetAssume() {
        // Both containers key on Activity.id and turn it back into an Activity through the registry,
        // and both pack ids into an int bit mask: the two numberings must agree and fit in 32 bits.
        final List<Activity> all = allActivities();
        assertTrue(all.size() <= Integer.SIZE, "an int bit mask holds at most 32 activities, found " + all.size());
        for (final Activity activity : all) {
            assertEquals(BuiltInRegistries.ACTIVITY.getId(activity), activity.id, "registry id vs Activity.id of " + activity);
            assertSame(activity, BuiltInRegistries.ACTIVITY.byIdOrThrow(activity.id));
        }
    }

    @Test
    void emptyMapAnswersAbsent() {
        final ActivityArrayMap<String> map = map();
        assertTrue(map.isEmpty());
        assertEquals(0, map.size());
        assertNull(map.get(Activity.CORE));
        assertNull(map.getValue(Activity.CORE.id));
        assertFalse(map.containsKey(Activity.CORE));
        assertNull(map.remove(Activity.CORE));
        assertFalse(map.keySet().iterator().hasNext());
        assertFalse(map.values().iterator().hasNext());
        assertFalse(map.entrySet().iterator().hasNext());
    }

    @Test
    void putReturnsThePreviousValueAndReplacesInPlace() {
        final ActivityArrayMap<String> map = map();
        assertNull(map.put(Activity.IDLE, "a"));
        assertNull(map.put(Activity.WORK, "b"));
        assertEquals("a", map.put(Activity.IDLE, "a2"));

        assertEquals(2, map.size());
        assertEquals("a2", map.get(Activity.IDLE));
        assertEquals("a2", map.getValue(Activity.IDLE.id));
        assertEquals(List.of(Activity.IDLE, Activity.WORK), new ArrayList<>(map.keySet()), "replacing keeps the slot");
    }

    @Test
    void puttingNullRemovesAnExistingEntryAndIgnoresAnAbsentOne() {
        final ActivityArrayMap<String> map = map();
        map.put(Activity.IDLE, "a");

        assertNull(map.put(Activity.WORK, null), "absent key and null value is a no-op");
        assertEquals(1, map.size());
        assertFalse(map.containsKey(Activity.WORK));

        assertEquals("a", map.put(Activity.IDLE, null), "null removes and reports what was there");
        assertTrue(map.isEmpty());
        assertFalse(map.containsKey(Activity.IDLE));
        assertNull(map.get(Activity.IDLE));
    }

    @Test
    void holdsEveryActivityAcrossGrowthInInsertionOrder() {
        final List<Activity> all = allActivities();
        // start from a zero length backing array so every growth step is exercised
        final ActivityArrayMap<Integer> map = new ActivityArrayMap<>(new Integer[0]);
        for (int i = all.size() - 1; i >= 0; i--) {
            assertNull(map.put(all.get(i), i));
        }

        assertEquals(all.size(), map.size());
        final List<Activity> reversed = new ArrayList<>(all);
        java.util.Collections.reverse(reversed);
        assertEquals(reversed, new ArrayList<>(map.keySet()), "iteration is insertion order, not id order");
        for (int i = 0; i < all.size(); i++) {
            assertEquals(i, map.get(all.get(i)));
            assertTrue(map.containsKey(all.get(i)));
        }
    }

    @Test
    void aBackingArrayLargerThanTheContentIsUsedAsIs() {
        final String[] backing = new String[4];
        final ActivityArrayMap<String> map = new ActivityArrayMap<>(backing);
        map.put(Activity.CORE, "x");
        assertEquals(1, map.size());
        assertSame(backing, map.v, "no reallocation while the capacity suffices");
        assertEquals("x", backing[0]);
    }

    @Test
    void removeKeepsTheRemainingOrderAndTheKeyMask() {
        final ActivityArrayMap<String> map = map();
        map.put(Activity.CORE, "core");
        map.put(Activity.IDLE, "idle");
        map.put(Activity.WORK, "work");
        map.put(Activity.PLAY, "play");

        assertEquals("idle", map.remove(Activity.IDLE));
        assertNull(map.remove(Activity.IDLE), "second remove finds nothing");
        assertEquals(List.of(Activity.CORE, Activity.WORK, Activity.PLAY), new ArrayList<>(map.keySet()));
        assertFalse(map.containsKey(Activity.IDLE));
        assertNull(map.get(Activity.IDLE));
        assertNull(map.v[3], "the vacated slot no longer references the value");

        map.put(Activity.IDLE, "again");
        assertEquals(List.of(Activity.CORE, Activity.WORK, Activity.PLAY, Activity.IDLE), new ArrayList<>(map.keySet()));
        assertEquals("again", map.get(Activity.IDLE));
    }

    @Test
    void keysOfTheWrongTypeAreAbsentNotAnError() {
        final ActivityArrayMap<String> map = map();
        map.put(Activity.CORE, "core");

        assertNull(map.get("core"));
        assertNull(map.get(null));
        assertFalse(map.containsKey("core"));
        assertFalse(map.containsKey(null));
        assertNull(map.remove("core"));
        assertNull(map.remove(null));
        assertEquals(1, map.size());
    }

    @Test
    void clearEmptiesTheMapDropsReferencesAndAllowsReuse() {
        final ActivityArrayMap<String> map = map();
        map.put(Activity.CORE, "a");
        map.put(Activity.IDLE, "b");
        map.clear();

        assertTrue(map.isEmpty());
        assertFalse(map.containsKey(Activity.CORE));
        assertNull(map.get(Activity.IDLE));
        assertNull(map.v[0]);
        assertNull(map.v[1]);

        map.put(Activity.IDLE, "c");
        assertEquals(Map.of(Activity.IDLE, "c"), map);
    }

    @Test
    void containsValueAndTheValuesViewAgree() {
        final ActivityArrayMap<String> map = map();
        map.put(Activity.CORE, "a");
        map.put(Activity.IDLE, "b");

        assertTrue(map.containsValue("b"));
        assertFalse(map.containsValue("zzz"));
        assertFalse(map.containsValue(null));
        assertTrue(map.values().contains("a"));
        assertEquals(List.of("a", "b"), new ArrayList<>(map.values()));
        assertEquals(2, map.values().size());
        assertEquals(2, map.keySet().size());
        assertTrue(map.keySet().contains(Activity.CORE));
        assertFalse(map.keySet().contains(Activity.WORK));
    }

    @Test
    void putAllCopiesEveryEntryAndNullsRemove() {
        final ActivityArrayMap<String> map = map();
        map.put(Activity.REST, "old");
        final Map<Activity, String> source = new HashMap<>();
        source.put(Activity.CORE, "a");
        source.put(Activity.IDLE, "b");
        source.put(Activity.REST, null);

        map.putAll(source);

        assertEquals(2, map.size());
        assertEquals("a", map.get(Activity.CORE));
        assertEquals("b", map.get(Activity.IDLE));
        assertFalse(map.containsKey(Activity.REST), "a null value in the source removes, as put(k, null) does");
    }

    @Test
    void iteratorsRemoveTheLastReturnedElementAndContinue() {
        for (final String view : List.of("keys", "values", "entries")) {
            final ActivityArrayMap<String> map = map();
            map.put(Activity.CORE, "core");
            map.put(Activity.IDLE, "idle");
            map.put(Activity.WORK, "work");
            map.put(Activity.PLAY, "play");

            final Iterator<?> it = switch (view) {
                case "keys" -> map.keySet().iterator();
                case "values" -> map.values().iterator();
                default -> map.entrySet().iterator();
            };
            int index = 0;
            while (it.hasNext()) {
                it.next();
                if (index++ % 2 == 0) {
                    it.remove(); // removes the 1st and the 3rd returned element
                }
            }

            assertEquals(Map.of(Activity.IDLE, "idle", Activity.PLAY, "play"), map, view + " view");
            assertEquals(List.of(Activity.IDLE, Activity.PLAY), new ArrayList<>(map.keySet()), view + " view keeps order");
        }
    }

    @Test
    void iteratorMisuseIsReportedNotSwallowed() {
        final ActivityArrayMap<String> map = map();
        map.put(Activity.CORE, "core");

        final Iterator<Activity> keys = map.keySet().iterator();
        assertThrows(IllegalStateException.class, keys::remove, "remove before next");
        keys.next();
        keys.remove();
        assertThrows(IllegalStateException.class, keys::remove, "second remove for one next");
        assertThrows(NoSuchElementException.class, keys::next, "exhausted");
        assertThrows(NoSuchElementException.class, () -> map.values().iterator().next());
        assertThrows(NoSuchElementException.class, () -> map.entrySet().iterator().next());
    }

    @Test
    void entriesReadAndWriteThrough() {
        final ActivityArrayMap<String> map = map();
        map.put(Activity.CORE, "a");
        map.put(Activity.IDLE, "b");

        final Map.Entry<Activity, String> first = map.entrySet().iterator().next();
        assertSame(Activity.CORE, first.getKey());
        assertEquals("a", first.getValue());
        assertEquals("a", first.setValue("a2"));

        assertEquals("a2", map.get(Activity.CORE), "setValue lands in the map");
        assertEquals("a2", first.getValue());
        assertTrue(map.entrySet().contains(Map.entry(Activity.CORE, "a2")));
        assertFalse(map.entrySet().contains(Map.entry(Activity.CORE, "a")));
        assertFalse(map.entrySet().contains(Map.entry(Activity.WORK, "a2")));
        assertFalse(map.entrySet().contains("not an entry"));
    }

    @Test
    void equalsAndHashCodeFollowTheMapContractAgainstAHashMap() {
        final ActivityArrayMap<String> map = map();
        map.put(Activity.WORK, "w");
        map.put(Activity.CORE, "c");
        final Map<Activity, String> reference = new HashMap<>();
        reference.put(Activity.CORE, "c");
        reference.put(Activity.WORK, "w");

        assertEquals(reference, map);
        assertEquals(map, reference, "HashMap.equals(map) walks its own entries through map.get");
        assertEquals(reference.hashCode(), map.hashCode());

        reference.put(Activity.CORE, "other");
        assertFalse(map.equals(reference), "differing value");
        reference.put(Activity.CORE, "c");
        reference.put(Activity.IDLE, "i");
        assertFalse(map.equals(reference), "differing size");
        assertFalse(map.equals("a string"));
        assertTrue(map.equals(map));
    }

    @Test
    void entryHashCodeMatchesTheHashMapEntry() {
        final ActivityArrayMap<String> map = map();
        map.put(Activity.WORK, "w");
        final Map.Entry<Activity, String> mine = map.entrySet().iterator().next();
        final Map.Entry<Activity, String> theirs = Map.entry(Activity.WORK, "w");

        assertEquals(theirs.hashCode(), mine.hashCode());
        assertEquals(mine, theirs);
        assertEquals(map.hashCode(), mine.hashCode(), "a single entry map hashes as its entry");
    }

    @Test
    @Timeout(30)
    void twoWritersTakingTurnsSeeEachOthersEntriesAndTheLastWriterWins() throws Exception {
        // Not a concurrency claim: ActivityArrayMap has no synchronisation, see the class comment.
        // What is guaranteed is that handing the map over with a happens-before edge is enough.
        final ActivityArrayMap<String> map = map();
        final Thread first = new Thread(() -> {
            map.put(Activity.CORE, "first-core");
            map.put(Activity.IDLE, "first-idle");
            map.put(Activity.WORK, "first-work");
        }, "writer-1");
        final Thread second = new Thread(() -> {
            map.put(Activity.WORK, "second-work");
            map.put(Activity.PLAY, "second-play");
            map.remove(Activity.CORE);
        }, "writer-2");

        first.start();
        first.join();
        second.start();
        second.join();

        assertEquals(Map.of(Activity.IDLE, "first-idle", Activity.WORK, "second-work", Activity.PLAY, "second-play"), map);
        assertEquals(List.of(Activity.IDLE, Activity.WORK, Activity.PLAY), new ArrayList<>(map.keySet()));
    }

    @Test
    void twoMapsDoNotShareState() {
        final ActivityArrayMap<String> a = map();
        final ActivityArrayMap<String> b = map();
        a.put(Activity.CORE, "a");
        b.put(Activity.CORE, "b");
        b.remove(Activity.CORE);

        assertEquals("a", a.get(Activity.CORE));
        assertTrue(b.isEmpty());
    }
}
