package dev.iyanz.aurora.engine.world;

import com.mojang.serialization.Codec;
import com.mojang.serialization.DataResult;
import com.mojang.serialization.Dynamic;
import com.mojang.serialization.DynamicOps;
import com.mojang.serialization.MapCodec;
import com.mojang.serialization.MapLike;
import com.mojang.serialization.RecordBuilder;
import com.mojang.serialization.codecs.RecordCodecBuilder;
import dev.iyanz.aurora.engine.util.LegacyNames;
import dev.iyanz.aurora.engine.util.LockedReference;
import java.util.Optional;
import java.util.stream.Stream;
import org.slf4j.LoggerFactory;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.dedicated.DedicatedServer;

/**
 * Per world view and simulation distance override. When the value is <= 0, it uses the value from the server
 * properties.
 *
 * @param viewDistance
 *     the view distance override
 * @param simulationDistance
 *     the simulation distance override
 */
public record PerWorldDistanceConfig(
    LockedReference<Integer> viewDistance, LockedReference<Integer> simulationDistance
) {
    public static PerWorldDistanceConfig DEFAULT = new PerWorldDistanceConfig(Optional.empty(), Optional.empty());
    public static final Codec<PerWorldDistanceConfig> CODEC = RecordCodecBuilder.create(i -> i.group(
        Codec.INT.optionalFieldOf("ViewDistance").orElse(Optional.empty()).forGetter(PerWorldDistanceConfig::viewDistanceAsOptional),
        Codec.INT.optionalFieldOf("SimulationDistance").orElse(Optional.empty()).forGetter(PerWorldDistanceConfig::simulationDistanceAsOptional)
    ).apply(i, PerWorldDistanceConfig::new));

    /** Key of this config inside the {@code paper:level_overrides} saved data. */
    public static final String LEVEL_OVERRIDES_KEY = "Aurora.Distance";
    /** The Canvas-era key: still read and never written; its removal awaits an owner decision. */
    public static final String LEGACY_LEVEL_OVERRIDES_KEY = "Canvas.Distance";

    /**
     * The {@code paper:level_overrides} field: written as {@value #LEVEL_OVERRIDES_KEY}; read from it, else from
     * {@value #LEGACY_LEVEL_OVERRIDES_KEY} with a one-time deprecation line. The overrides are saved again whenever
     * they are dirty (game time changes every tick), so an old world is rewritten with the new key on its first save
     * and the old key is gone from then on. With neither key present the field is optional: the world has no override
     * and decodes to {@link #unset()} (a world whose overrides Paper wrote never has either key; failing there made
     * Paper discard the whole {@code paper:level_overrides} record, its spawn and game time included). The deprecation
     * line is logged only when the old key is actually read.
     */
    public static final MapCodec<PerWorldDistanceConfig> LEVEL_OVERRIDES_FIELD = new MapCodec<>() {
        @Override
        public <T> Stream<T> keys(final DynamicOps<T> ops) {
            return Stream.of(ops.createString(LEVEL_OVERRIDES_KEY));
        }

        @Override
        public <T> DataResult<PerWorldDistanceConfig> decode(final DynamicOps<T> ops, final MapLike<T> input) {
            final T current = input.get(LEVEL_OVERRIDES_KEY);
            if (current != null) {
                return CODEC.parse(ops, current);
            }
            final T legacy = input.get(LEGACY_LEVEL_OVERRIDES_KEY);
            if (legacy != null) {
                warnLegacyKey();
                return CODEC.parse(ops, legacy);
            }
            return DataResult.success(unset());
        }

        @Override
        public <T> RecordBuilder<T> encode(final PerWorldDistanceConfig input, final DynamicOps<T> ops, final RecordBuilder<T> prefix) {
            return prefix.add(LEVEL_OVERRIDES_KEY, CODEC.encodeStart(ops, input));
        }

        @Override
        public String toString() {
            return "PerWorldDistanceConfig[" + LEVEL_OVERRIDES_KEY + " | " + LEGACY_LEVEL_OVERRIDES_KEY + "]";
        }
    };

    /**
     * The config stored in a raw {@code level.dat} being converted to level overrides: {@value #LEVEL_OVERRIDES_KEY},
     * else {@value #LEGACY_LEVEL_OVERRIDES_KEY}, else {@link #unset()}. Parse errors are logged, not thrown.
     */
    public static PerWorldDistanceConfig fromRawLevelData(final Dynamic<?> levelData) {
        Dynamic<?> stored = levelData.get(LEVEL_OVERRIDES_KEY).result().orElse(null);
        if (stored == null) {
            stored = levelData.get(LEGACY_LEVEL_OVERRIDES_KEY).result().orElse(null);
            if (stored != null) {
                warnLegacyKey();
            }
        }
        if (stored == null) {
            return unset();
        }
        return stored.read(CODEC).resultOrPartial(LoggerFactory.getLogger("Aurora")::error).orElseGet(PerWorldDistanceConfig::unset);
    }

    /**
     * A new config with no view or simulation override. Readers return this rather than the shared {@link #DEFAULT},
     * whose references are mutable, so one world's setter can never change another world's config.
     */
    public static PerWorldDistanceConfig unset() {
        return new PerWorldDistanceConfig(Optional.empty(), Optional.empty());
    }

    private static void warnLegacyKey() {
        LegacyNames.warnOnce("level-data:" + LEGACY_LEVEL_OVERRIDES_KEY, "Read the per-world distance from the deprecated "
            + LEGACY_LEVEL_OVERRIDES_KEY + " key; it is saved as " + LEVEL_OVERRIDES_KEY + " from now on (the old key is "
            + "still read; its removal awaits an owner decision).");
    }

    // this is for the codec, nothing much else
    @SuppressWarnings("OptionalUsedAsFieldOrParameterType")
    public PerWorldDistanceConfig(final Optional<Integer> view, final Optional<Integer> simulation) {
        this(
            view.map(LockedReference::new).orElse(new LockedReference<>(null)),
            simulation.map(LockedReference::new).orElse(new LockedReference<>(null))
        );
    }

    public PerWorldDistanceConfig(
        final LockedReference<Integer> viewDistance,
        final LockedReference<Integer> simulationDistance
    ) {

        // for backwards compat with old system. we used to store
        // the -1 value, so this is a good way to clean this up

        final Integer view = viewDistance.getValue();
        final Integer simulation = simulationDistance.getValue();

        this.viewDistance = Integer.valueOf(-1).equals(view)
            ? new LockedReference<>(null)
            : viewDistance;

        this.simulationDistance = Integer.valueOf(-1).equals(simulation)
            ? new LockedReference<>(null)
            : simulationDistance;
    }

    public int snapshotViewDistance() {
        final Integer raw = viewDistance().getValue();
        if (raw == null) {
            return -1;
        }
        return raw;
    }

    public int snapshotSimulationDistance() {
        final Integer raw = simulationDistance().getValue();
        if (raw == null) {
            return -1;
        }
        return raw;
    }

    public Optional<Integer> viewDistanceAsOptional() {
        return viewDistance.asOptional();
    }

    public Optional<Integer> simulationDistanceAsOptional() {
        return simulationDistance.asOptional();
    }

    public int viewDistanceOrDefault() {
        final int snapshot = snapshotViewDistance();
        return snapshot <= 0 ? ((DedicatedServer) MinecraftServer.getServer()).settings.getProperties().viewDistance.get() : snapshot;
    }

    public int simulationDistanceOrDefault() {
        final int snapshot = snapshotSimulationDistance();
        return snapshot <= 0 ? ((DedicatedServer) MinecraftServer.getServer()).settings.getProperties().simulationDistance.get() : snapshot;
    }

    public boolean isViewDistanceOverridden() {
        return this.viewDistance().isSet();
    }

    public boolean isSimulationDistanceOverridden() {
        return this.simulationDistance().isSet();
    }

    public void setViewDistance(final int distance) {
        if (distance == -1) {
            viewDistance().unset();
        }
        else {
            viewDistance().swapValue(distance);
        }
    }

    public void setSimulationDistance(final int distance) {
        if (distance == -1) {
            simulationDistance().unset();
        }
        else {
            simulationDistance().swapValue(distance);
        }
    }
}
