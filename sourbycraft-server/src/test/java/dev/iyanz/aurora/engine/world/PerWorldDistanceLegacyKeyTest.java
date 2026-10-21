package dev.iyanz.aurora.engine.world;

import com.mojang.serialization.Codec;
import com.mojang.serialization.Dynamic;
import com.mojang.serialization.codecs.RecordCodecBuilder;
import dev.iyanz.aurora.engine.util.LegacyNames;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.NbtOps;
import net.minecraft.nbt.Tag;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.parallel.ResourceLock;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The per-world distance in {@code paper:level_overrides} moved from {@code Canvas.Distance} to
 * {@code Aurora.Distance} (owner decision 2026-10-21): the old key is read once, the next save writes only the new
 * key, so a round trip migrates the data without losing it.
 */
@ResourceLock("dev.iyanz.aurora.engine.util.LegacyNames")
class PerWorldDistanceLegacyKeyTest {

    /** A stand-in for the PaperLevelOverrides record: one other field plus the distance field. */
    private record Holder(long gameTime, PerWorldDistanceConfig distance) {
    }

    private static final Codec<Holder> HOLDER = RecordCodecBuilder.create(i -> i.group(
        Codec.LONG.fieldOf("game_time").forGetter(Holder::gameTime),
        PerWorldDistanceConfig.LEVEL_OVERRIDES_FIELD.forGetter(Holder::distance)
    ).apply(i, Holder::new));

    private final List<String> warnings = new ArrayList<>();

    @BeforeEach
    void install() {
        LegacyNames.installForTesting(name -> null, this.warnings::add);
    }

    @AfterEach
    void restore() {
        LegacyNames.resetForTesting();
    }

    private static CompoundTag distance(final int view, final int simulation) {
        final CompoundTag tag = new CompoundTag();
        tag.putInt("ViewDistance", view);
        tag.putInt("SimulationDistance", simulation);
        return tag;
    }

    private static CompoundTag stored(final String key, final CompoundTag value) {
        final CompoundTag tag = new CompoundTag();
        tag.putLong("game_time", 42L);
        tag.put(key, value);
        return tag;
    }

    @Test
    void canvasEraKeyIsReadAndTheNextSaveWritesOnlyTheAuroraKey() {
        final Holder read = HOLDER.parse(NbtOps.INSTANCE, stored("Canvas.Distance", distance(7, 5))).getOrThrow();

        assertEquals(Optional.of(7), read.distance().viewDistanceAsOptional());
        assertEquals(Optional.of(5), read.distance().simulationDistanceAsOptional());
        assertEquals(1, this.warnings.size());
        assertTrue(this.warnings.getFirst().contains("Canvas.Distance") && this.warnings.getFirst().contains("Aurora.Distance"));

        final CompoundTag saved = (CompoundTag) HOLDER.encodeStart(NbtOps.INSTANCE, read).getOrThrow();
        assertFalse(saved.contains("Canvas.Distance"), "the old key is never written");
        assertEquals(distance(7, 5), saved.get("Aurora.Distance"));

        final Holder again = HOLDER.parse(NbtOps.INSTANCE, saved).getOrThrow();
        assertEquals(Optional.of(7), again.distance().viewDistanceAsOptional());
        assertEquals(1, this.warnings.size(), "a migrated file reads silently");
    }

    @Test
    void auroraKeyWinsWhenBothArePresent() {
        final CompoundTag both = stored("Aurora.Distance", distance(9, 9));
        both.put("Canvas.Distance", distance(3, 3));

        final Holder read = HOLDER.parse(NbtOps.INSTANCE, both).getOrThrow();

        assertEquals(Optional.of(9), read.distance().viewDistanceAsOptional());
        assertEquals(List.of(), this.warnings);
    }

    @Test
    void missingKeyStillFailsLikeTheFormerRequiredField() {
        final CompoundTag none = new CompoundTag();
        none.putLong("game_time", 1L);
        assertTrue(HOLDER.parse(NbtOps.INSTANCE, none).isError());
    }

    @Test
    void rawLevelDataPrefersTheNewKeyFallsBackToTheOldAndDefaultsWhenAbsent() {
        final Dynamic<Tag> legacy = new Dynamic<>(NbtOps.INSTANCE, stored("Canvas.Distance", distance(6, 4)));
        assertEquals(Optional.of(6), PerWorldDistanceConfig.fromRawLevelData(legacy).viewDistanceAsOptional());
        assertEquals(1, this.warnings.size());

        final Dynamic<Tag> current = new Dynamic<>(NbtOps.INSTANCE, stored("Aurora.Distance", distance(8, 4)));
        assertEquals(Optional.of(8), PerWorldDistanceConfig.fromRawLevelData(current).viewDistanceAsOptional());

        final Dynamic<Tag> absent = new Dynamic<>(NbtOps.INSTANCE, new CompoundTag());
        assertEquals(Optional.empty(), PerWorldDistanceConfig.fromRawLevelData(absent).viewDistanceAsOptional());
    }
}
