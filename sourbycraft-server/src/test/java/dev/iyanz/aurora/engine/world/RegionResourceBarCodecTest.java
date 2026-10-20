package dev.iyanz.aurora.engine.world;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonPrimitive;
import com.mojang.serialization.DataResult;
import com.mojang.serialization.JsonOps;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * OBS-14 engine world: the codecs behind a player's saved {@code TpsBar}/{@code RamBar} entries
 * ({@link RegionResourceBar.Entry#CODEC}, {@link RegionResourceBar.Placement#CODEC}). A player file
 * written by an older or newer build, or edited by hand, can hold a placement this build does not
 * know; reading it must be a codec error the caller can fall back from, never an exception out of the
 * codec, because the exception would abort the player's data load.
 */
class RegionResourceBarCodecTest {

    private static JsonObject entry(final boolean enabled, final String placement) {
        final JsonObject object = new JsonObject();
        object.addProperty("enabled", enabled);
        object.addProperty("placement", placement);
        return object;
    }

    @Test
    void placementRoundTripsByName() {
        for (final RegionResourceBar.Placement placement : RegionResourceBar.Placement.values()) {
            final JsonElement json = RegionResourceBar.Placement.CODEC.encodeStart(JsonOps.INSTANCE, placement).getOrThrow();
            assertEquals(new JsonPrimitive(placement.name()), json);
            assertEquals(placement, RegionResourceBar.Placement.CODEC.parse(JsonOps.INSTANCE, json).getOrThrow());
        }
    }

    @Test
    void entryRoundTrips() {
        final RegionResourceBar.Entry original = new RegionResourceBar.Entry(true, RegionResourceBar.Placement.ACTION_BAR);
        final JsonElement json = RegionResourceBar.Entry.CODEC.encodeStart(JsonOps.INSTANCE, original).getOrThrow();
        assertEquals(entry(true, "ACTION_BAR"), json);
        assertEquals(original, RegionResourceBar.Entry.CODEC.parse(JsonOps.INSTANCE, json).getOrThrow());
    }

    @Test
    void missingFieldsTakeTheDocumentedDefaults() {
        final RegionResourceBar.Entry parsed = RegionResourceBar.Entry.CODEC.parse(JsonOps.INSTANCE, new JsonObject()).getOrThrow();
        assertEquals(new RegionResourceBar.Entry(false, RegionResourceBar.Placement.BOSS_BAR), parsed);
        assertEquals(RegionResourceBar.Entry.FALLBACK, parsed);
    }

    @Test
    void anUnknownPlacementIsACodecErrorNotAnException() {
        final DataResult<RegionResourceBar.Placement> result =
            RegionResourceBar.Placement.CODEC.parse(JsonOps.INSTANCE, new JsonPrimitive("SIDEWAYS"));

        assertTrue(result.error().isPresent(), "an unknown name must come back as a DataResult error");
    }

    @Test
    void anEntryWithAnUnknownPlacementCanBeFallenBackFromByTheCaller() {
        final DataResult<RegionResourceBar.Entry> result =
            RegionResourceBar.Entry.CODEC.parse(JsonOps.INSTANCE, entry(true, "SIDEWAYS"));

        // ServerPlayer reads this as `.orElse(Entry.FALLBACK)`: that only works when the parse returns
        assertTrue(result.result().isEmpty() || result.error().isPresent());
    }
}
