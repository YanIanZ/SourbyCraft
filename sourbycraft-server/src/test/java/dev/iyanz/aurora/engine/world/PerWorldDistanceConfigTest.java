package dev.iyanz.aurora.engine.world;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.mojang.serialization.JsonOps;
import dev.iyanz.aurora.engine.util.LockedReference;
import java.util.Optional;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * OBS-14 engine world: {@link PerWorldDistanceConfig}, a world's view and simulation distance
 * overrides as stored in {@code level.dat}-side saved data and changed by {@code worlddistance}.
 * The "or default" readers fall back to {@code server.properties} through the running server, so only
 * the override side (stored value, unset, the legacy {@code -1} spelling, the codec) is covered.
 */
class PerWorldDistanceConfigTest {

    @Test
    void anEmptyConfigHasNoOverrides() {
        final PerWorldDistanceConfig config = new PerWorldDistanceConfig(Optional.empty(), Optional.empty());
        assertEquals(-1, config.snapshotViewDistance());
        assertEquals(-1, config.snapshotSimulationDistance());
        assertFalse(config.isViewDistanceOverridden());
        assertFalse(config.isSimulationDistanceOverridden());
        assertEquals(Optional.empty(), config.viewDistanceAsOptional());
    }

    @Test
    void storedValuesAreReadBackAndOverridden() {
        final PerWorldDistanceConfig config = new PerWorldDistanceConfig(Optional.of(10), Optional.of(6));
        assertEquals(10, config.snapshotViewDistance());
        assertEquals(6, config.snapshotSimulationDistance());
        assertTrue(config.isViewDistanceOverridden());
        assertTrue(config.isSimulationDistanceOverridden());
        assertEquals(10, config.viewDistanceOrDefault(), "an override never consults the server");
        assertEquals(6, config.simulationDistanceOrDefault());
    }

    @Test
    void theLegacyMinusOneIsTreatedAsNotSet() {
        final PerWorldDistanceConfig fromOptionals = new PerWorldDistanceConfig(Optional.of(-1), Optional.of(8));
        assertFalse(fromOptionals.isViewDistanceOverridden());
        assertEquals(-1, fromOptionals.snapshotViewDistance());
        assertEquals(8, fromOptionals.snapshotSimulationDistance());

        final PerWorldDistanceConfig fromReferences = new PerWorldDistanceConfig(new LockedReference<>(5), new LockedReference<>(-1));
        assertEquals(5, fromReferences.snapshotViewDistance());
        assertFalse(fromReferences.isSimulationDistanceOverridden());
    }

    @Test
    void settersStoreAndMinusOneClears() {
        final PerWorldDistanceConfig config = new PerWorldDistanceConfig(Optional.empty(), Optional.empty());

        config.setViewDistance(12);
        config.setSimulationDistance(9);
        assertEquals(12, config.snapshotViewDistance());
        assertEquals(9, config.snapshotSimulationDistance());

        config.setViewDistance(-1);
        assertFalse(config.isViewDistanceOverridden());
        assertEquals(9, config.snapshotSimulationDistance(), "the two settings are independent");

        config.setSimulationDistance(-1);
        assertFalse(config.isSimulationDistanceOverridden());
    }

    @Test
    void theCodecWritesOnlyWhatIsSetAndReadsItBack() {
        final PerWorldDistanceConfig config = new PerWorldDistanceConfig(Optional.of(10), Optional.empty());

        final JsonElement json = PerWorldDistanceConfig.CODEC.encodeStart(JsonOps.INSTANCE, config).getOrThrow();
        final JsonObject object = json.getAsJsonObject();
        assertEquals(10, object.get("ViewDistance").getAsInt());
        assertFalse(object.has("SimulationDistance"));

        final PerWorldDistanceConfig back = PerWorldDistanceConfig.CODEC.parse(JsonOps.INSTANCE, json).getOrThrow();
        assertEquals(10, back.snapshotViewDistance());
        assertFalse(back.isSimulationDistanceOverridden());
    }

    @Test
    void theCodecReadsOldSavedDataWithMinusOneAndMissingKeys() {
        final JsonObject old = new JsonObject();
        old.addProperty("ViewDistance", -1);
        old.addProperty("SimulationDistance", 4);
        final PerWorldDistanceConfig parsed = PerWorldDistanceConfig.CODEC.parse(JsonOps.INSTANCE, old).getOrThrow();
        assertFalse(parsed.isViewDistanceOverridden());
        assertEquals(4, parsed.snapshotSimulationDistance());

        final PerWorldDistanceConfig empty = PerWorldDistanceConfig.CODEC.parse(JsonOps.INSTANCE, new JsonObject()).getOrThrow();
        assertFalse(empty.isViewDistanceOverridden());
        assertFalse(empty.isSimulationDistanceOverridden());
    }
}
