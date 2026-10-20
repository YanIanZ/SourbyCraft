package io.canvasmc.canvas.region;

import static org.junit.jupiter.api.Assertions.*;

import it.unimi.dsi.fastutil.longs.Long2ReferenceOpenHashMap;
import it.unimi.dsi.fastutil.objects.ReferenceOpenHashSet;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.lang.reflect.Type;
import java.util.Arrays;
import java.util.List;
import java.util.Set;
import java.util.TreeSet;
import java.util.stream.Collectors;
import org.junit.jupiter.api.Test;

/**
 * The region API is interfaces only: nothing in this module throws or refuses. The documented refusals
 * ({@code NullPointerException} for an unloaded chunk, {@code IllegalStateException} from a thread that does not
 * own the region) are implemented by the server, so they cannot be asserted here. What this module does own and
 * what plugins compile against is checked: the exact method surface, the enum constants, and the one default
 * method.
 */
class RegionApiTest {
    private static Set<String> surface(final Class<?> type) {
        return Arrays.stream(type.getDeclaredMethods())
            .filter(m -> !m.isSynthetic())
            .map(m -> m.getGenericReturnType().getTypeName() + " " + m.getName() + "("
                + Arrays.stream(m.getGenericParameterTypes()).map(Type::getTypeName).collect(Collectors.joining(", "))
                + ")" + (Modifier.isAbstract(m.getModifiers()) ? "" : " default"))
            .collect(Collectors.toCollection(TreeSet::new));
    }

    private static final String P = "io.canvasmc.canvas.region.";
    private static final String HANDLE = P + "RegionThreadingTickManager$RegionHandle";
    private static final String REGION = P + "WorldRegionizer$ChunkRegion";
    private static final String REGION_STATE = P + "WorldRegionizer$ChunkRegion$State";
    private static final String DATA = P + "RegionTickData$IRegionizedData";
    private static final String CALLBACK_TYPE = DATA + "$IRegionizedCallback";
    private static final String FRAME = P + "RegionTickData$Frame";

    // Expected entries are "<generic return type> <name>(<generic parameter types>)": a changed return type,
    // parameter type or generic argument changes the binary signature plugins link against, so it must fail here.

    @Test
    void tickManagerSurfaceIsPinned() {
        assertEquals(Set.of(
            "float getTickRate()",
            "void setTickRate(float)",
            HANDLE + " getHandleAt(org.bukkit.Chunk)",
            HANDLE + " getGlobalRegionHandle()",
            "void sendUpdateToAllPlayers()"), surface(RegionThreadingTickManager.class));
        assertEquals(Set.of(
            "void pause()",
            "void play()",
            "void walk()",
            "void sprint(int)",
            "boolean doesRunGameElements()",
            "boolean isSprinting()"), surface(RegionThreadingTickManager.RegionHandle.class));
    }

    @Test
    void regionizerSurfaceIsPinned() {
        assertEquals(Set.of(
            "org.bukkit.World getWorld()",
            "void computeForAllChunkRegions(java.util.function.Consumer<" + REGION + ">)",
            "void computeForAllChunkRegionsUnsynchronized(java.util.function.Consumer<" + REGION + ">)",
            "int getSectionShift()",
            "int getRegionSectionMergeRadius()",
            "int getEmptySectionCreateRadius()",
            "double getMaxDeadRegionPercent()",
            REGION + " getRegionAtUnsynchronised(int, int)",
            REGION + " getRegionAtSynchronised(int, int)"), surface(WorldRegionizer.class));
        assertEquals(Set.of(
            "boolean hasNoAliveSections()",
            "double getDeadSectionPercent()",
            "long[] getOwnedPackedChunkPositions()",
            REGION_STATE + " getState()",
            P + "RegionTickData getTickData()",
            P + "WorldRegionizer getRegionizer()",
            "long getId()"), surface(WorldRegionizer.ChunkRegion.class));
    }

    @Test
    void tickDataSurfaceIsPinned() {
        assertEquals(Set.of(
            REGION + " getRegion()",
            "org.bukkit.World getWorld()",
            "T getOrCreateFromIRegionizedData(" + DATA + "<T>)",
            HANDLE + " getTickManager()",
            "double getMSPT(" + FRAME + ")",
            "double getTPS(" + FRAME + ")",
            "double getUtilization(" + FRAME + ")",
            "long getCurrentTick()",
            "java.util.concurrent.CompletableFuture<java.lang.Void> propagateSaveAllTicket(boolean)"),
            surface(RegionTickData.class));
        assertEquals(Set.of(
            "T createValue(" + P + "RegionTickData)",
            CALLBACK_TYPE + "<T> getCallback()"), surface(RegionTickData.IRegionizedData.class));
        assertEquals(Set.of(
            "void merge(T, T, long)",
            "void split(T, int, it.unimi.dsi.fastutil.longs.Long2ReferenceOpenHashMap<T>, it.unimi.dsi.fastutil.objects.ReferenceOpenHashSet<T>)",
            "long getRegionSectionCoordinates(int, int, int) default"),
            surface(RegionTickData.IRegionizedData.IRegionizedCallback.class));
    }

    @Test
    void frameAndStateConstantsAreOrderedAndNamedAsPluginsExpect() {
        assertEquals(List.of("_5_SECONDS", "_15_SECONDS", "_1_MINUTE", "_5_MINUTES", "_15_MINUTES"),
            Arrays.stream(RegionTickData.Frame.values()).map(Enum::name).toList());
        assertEquals(List.of("TRANSIENT", "READY", "TICKING", "DEAD"),
            Arrays.stream(WorldRegionizer.ChunkRegion.State.values()).map(Enum::name).toList());
    }

    // ---- IRegionizedCallback#getRegionSectionCoordinates ------------------------------------------------------

    private static final RegionTickData.IRegionizedData.IRegionizedCallback<Object> CALLBACK =
        new RegionTickData.IRegionizedData.IRegionizedCallback<>() {
            @Override
            public void merge(final Object from, final Object into, final long fromTickOffset) {
            }

            @Override
            public void split(final Object from, final int chunkToRegionShift,
                              final Long2ReferenceOpenHashMap<Object> regionToData, final ReferenceOpenHashSet<Object> dataSet) {
            }
        };

    private static int unpackX(final long packed) {
        return (int) (packed & 0xFFFFFFFFL);
    }

    private static int unpackZ(final long packed) {
        return (int) (packed >>> 32);
    }

    @Test
    void sectionCoordinatesAtShiftZeroUseTheDocumentedChunkPackingLayout() {
        // ChunkRegion#getOwnedPackedChunkPositions documents: ((long) z & 0xFFFFFFFFL) << 32 | ((long) x & 0xFFFFFFFFL)
        for (final int[] c : new int[][]{{0, 0}, {1, 2}, {-1, -1}, {-5, 7}, {123456, -654321}, {Integer.MAX_VALUE, Integer.MIN_VALUE}}) {
            final long documented = (((long) c[1]) & 0xFFFFFFFFL) << 32 | (((long) c[0]) & 0xFFFFFFFFL);
            assertEquals(documented, CALLBACK.getRegionSectionCoordinates(c[0], c[1], 0), Arrays.toString(c));
            assertEquals(c[0], unpackX(documented));
            assertEquals(c[1], unpackZ(documented));
        }
    }

    @Test
    void sectionCoordinatesShiftChunksDownToTheirRegionSection() {
        assertEquals(0L, CALLBACK.getRegionSectionCoordinates(0, 0, 4));
        assertEquals(0L, CALLBACK.getRegionSectionCoordinates(15, 15, 4), "chunk 15 is still in section 0 at shift 4");
        final long sixteen = CALLBACK.getRegionSectionCoordinates(16, 32, 4);
        assertEquals(1, unpackX(sixteen));
        assertEquals(2, unpackZ(sixteen));
        assertEquals(sixteen, CALLBACK.getRegionSectionCoordinates(31, 47, 4), "same section for the whole 16x16 block");
        assertNotEquals(sixteen, CALLBACK.getRegionSectionCoordinates(32, 32, 4));
    }

    @Test
    void negativeChunksUseFlooringShiftSoSectionsAreContiguousAcrossZero() {
        final long minusOne = CALLBACK.getRegionSectionCoordinates(-1, -1, 4);
        assertEquals(-1, unpackX(minusOne));
        assertEquals(-1, unpackZ(minusOne));
        assertEquals(minusOne, CALLBACK.getRegionSectionCoordinates(-16, -16, 4), "-16 is the first chunk of section -1");
        final long minusTwo = CALLBACK.getRegionSectionCoordinates(-17, 0, 4);
        assertEquals(-2, unpackX(minusTwo));
        assertEquals(0, unpackZ(minusTwo));
    }

    @Test
    void xAndZAreNotInterchangeableInTheKey() {
        assertNotEquals(CALLBACK.getRegionSectionCoordinates(1, 2, 0), CALLBACK.getRegionSectionCoordinates(2, 1, 0));
        final long key = CALLBACK.getRegionSectionCoordinates(7, 0, 0);
        assertEquals(7, unpackX(key));
        assertEquals(0, unpackZ(key));
    }

    @Test
    void theKeyMatchesTheFormulaInTheSplitJavadoc() {
        final int shift = 3;
        for (int x = -40; x <= 40; x += 7) {
            for (int z = -40; z <= 40; z += 9) {
                final int sx = x >> shift;
                final int sz = z >> shift;
                assertEquals(((long) sz << 32) | (sx & 0xFFFFFFFFL), CALLBACK.getRegionSectionCoordinates(x, z, shift));
            }
        }
    }

    @Test
    void thatMethodIsTheOnlyNonAbstractMemberSoImplementersOnlyWriteMergeAndSplit() {
        final long concrete = Arrays.stream(RegionTickData.IRegionizedData.IRegionizedCallback.class.getDeclaredMethods())
            .filter(m -> !Modifier.isAbstract(m.getModifiers()) && !m.isSynthetic()).count();
        assertEquals(1, concrete);
        final Method m = Arrays.stream(RegionTickData.IRegionizedData.IRegionizedCallback.class.getDeclaredMethods())
            .filter(x -> !Modifier.isAbstract(x.getModifiers())).findFirst().orElseThrow();
        assertEquals("getRegionSectionCoordinates", m.getName());
        assertTrue(m.isDefault());
    }
}
