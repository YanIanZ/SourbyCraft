package dev.iyanz.aurora.engine.configuration;

import dev.iyanz.aurora.engine.GlobalConfiguration;
import dev.iyanz.aurora.engine.WorldConfig;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import net.minecraft.SharedConstants;
import net.minecraft.server.Bootstrap;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.yaml.snakeyaml.DumperOptions;
import org.yaml.snakeyaml.Yaml;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * OBS-14 engine util/migration (config side): what an upgrade does to an existing
 * {@code config/canvas-server.yml} / {@code canvas-worlds.yml}. The key set of those files is
 * migrated by {@link ConfigurationProvider#buildSolidConfiguration}: keys the class gained are added
 * (with their documentation), keys the class no longer has are <b>removed</b>, the values the operator
 * set on surviving keys are kept, and a second run changes nothing.
 *
 * <p>Unknown keys are therefore NOT preserved by an upgrade. That is deliberate and necessary: the
 * file is then bound to the config class field by field and an unknown property would fail the load;
 * the removal is reported through {@code Resolver.onDiffRemove}. {@code Part.save} is the other
 * writer and it does leave unknown keys alone; both behaviours are pinned here.</p>
 *
 * <p>The scenarios run twice: on a small synthetic key set (exact, readable assertions) and on the
 * real {@code GlobalConfiguration} / {@code WorldConfig} classes (so a real key set round-trips),
 * always against a {@code @TempDir} path and never the working directory's {@code config/}.</p>
 */
class ConfigurationUpgradeTest {

    private static final int WRAP = 90;

    @BeforeAll
    static void bootstrapMinecraft() {
        SharedConstants.tryDetectVersion();
        Bootstrap.bootStrap();
    }

    // ------------------------------------------------------------------ synthetic key set

    public static class Sample extends Part {
        {
            option("name").docs("The display name.");
        }

        public String name = "default-name";
        public int count = 3;
        public Section section = new Section();

        public static class Section extends Part {
            {
                option("keepAlive").docs("Whether to keep alive.");
                option("tags").docs("Free tags.");
            }

            public boolean keepAlive = true;
            public List<String> tags = new ArrayList<>(List.of("a"));
        }
    }

    /** Records what the provider reported. */
    private static final class Recorder implements Resolver<Sample> {
        final List<String> added = new ArrayList<>();
        final List<String> removed = new ArrayList<>();
        Sample loaded;
        int finishes;

        @Override public void onDiffAdd(final String name) { this.added.add(name); }
        @Override public void onDiffRemove(final String name) { this.removed.add(name); }
        @Override public void onFinishLoad(final Sample instance) { this.loaded = instance; this.finishes++; }
    }

    private static Recorder build(final Path file) {
        final Recorder recorder = new Recorder();
        ConfigurationProvider.buildSolidConfiguration(file, Sample::new, WRAP, recorder, "Sample header");
        return recorder;
    }

    @Test
    void aMissingFileIsFloodFilledWithEveryKeyItsDocsAndTheHeader(@TempDir final Path dir) throws IOException {
        final Path file = dir.resolve("config").resolve("sample.yml");

        final Recorder recorder = build(file);

        final String text = Files.readString(file);
        assertTrue(text.startsWith("# Sample header"), text);
        assertTrue(text.contains("# The display name."), text);
        assertTrue(text.contains("# Whether to keep alive."), text);
        final Map<String, Object> parsed = load(file);
        assertEquals("default-name", parsed.get("name"));
        assertEquals(3, parsed.get("count"));
        assertEquals(Map.of("keep-alive", true, "tags", List.of("a")), parsed.get("section"));
        assertEquals(1, recorder.finishes);
        assertEquals("default-name", recorder.loaded.name);
        assertTrue(recorder.added.isEmpty(), "flood fill reports no per-key diff");
    }

    @Test
    void anOldKeySetGainsNewKeysLosesRetiredOnesAndKeepsTheOperatorsValues(@TempDir final Path dir) throws IOException {
        final Path file = dir.resolve("sample.yml");
        Files.writeString(file, """
            # operator note kept above the name
            name: "custom-name"
            legacy-flag: true
            ancient:
               a: 1
            section:
               keep-alive: false
               old-thing: 5
            """);

        final Recorder recorder = build(file);

        assertEquals(List.of("count", "section.tags"), recorder.added);
        assertEquals(Set.of("legacy-flag", "ancient", "section.old-thing"), new HashSet<>(recorder.removed));
        assertEquals(3, recorder.removed.size());
        final Map<String, Object> parsed = load(file);
        assertEquals("custom-name", parsed.get("name"), "operator value survives");
        assertEquals(3, parsed.get("count"), "added key takes the default");
        assertEquals(Map.of("keep-alive", false, "tags", List.of("a")), parsed.get("section"));
        assertFalse(parsed.containsKey("legacy-flag"));
        assertFalse(parsed.containsKey("ancient"));

        assertEquals("custom-name", recorder.loaded.name);
        assertEquals(3, recorder.loaded.count);
        assertFalse(recorder.loaded.section.keepAlive);
        assertEquals(List.of("a"), recorder.loaded.section.tags);

        final String text = Files.readString(file);
        assertTrue(text.contains("# operator note kept above the name"), "the operator's comment survives:\n" + text);
        assertTrue(text.contains("# Free tags."), "an added key arrives documented:\n" + text);
    }

    @Test
    void theUpgradeIsIdempotent(@TempDir final Path dir) throws IOException {
        final Path file = dir.resolve("sample.yml");
        Files.writeString(file, "name: \"custom-name\"\nlegacy-flag: true\n");

        build(file);
        final String afterFirst = Files.readString(file);
        final Recorder second = build(file);
        final String afterSecond = Files.readString(file);

        assertEquals(afterFirst, afterSecond, "second run rewrites the same bytes");
        assertTrue(second.added.isEmpty(), second.added.toString());
        assertTrue(second.removed.isEmpty(), second.removed.toString());
        assertEquals("custom-name", second.loaded.name);
        assertEquals(1, second.finishes);
    }

    @Test
    void aCurrentFileIsLeftAlone(@TempDir final Path dir) throws IOException {
        final Path file = dir.resolve("sample.yml");
        build(file);
        final String fresh = Files.readString(file);

        final Recorder again = build(file);

        assertEquals(fresh, Files.readString(file));
        assertTrue(again.added.isEmpty());
        assertTrue(again.removed.isEmpty());
    }

    @Test
    void aSectionThatBecameAScalarIsReplacedByTheDefaultSection(@TempDir final Path dir) throws IOException {
        final Path file = dir.resolve("sample.yml");
        Files.writeString(file, "name: \"n\"\ncount: 7\nsection: 5\n");

        final Recorder recorder = build(file);

        assertEquals(List.of("section"), recorder.removed);
        assertEquals(List.of("section"), recorder.added);
        assertEquals(Map.of("keep-alive", true, "tags", List.of("a")), load(file).get("section"));
        assertEquals(7, recorder.loaded.count);
    }

    @Test
    void anEmptiedFileIsFloodFilledAgain(@TempDir final Path dir) throws IOException {
        final Path file = dir.resolve("sample.yml");
        Files.writeString(file, "");

        final Recorder recorder = build(file);

        assertEquals("default-name", load(file).get("name"));
        assertEquals("default-name", recorder.loaded.name);
    }

    @Test
    void aFileOfOnlyCommentsGetsEveryKeyAddedAndKeepsItsComment(@TempDir final Path dir) throws IOException {
        // SnakeYAML composes this to a mapping whose tuple list is immutable and empty; the diff used to
        // throw UnsupportedOperationException from List.add and the server could not boot
        final Path file = dir.resolve("sample.yml");
        Files.writeString(file, "# I commented every option out\n");

        final Recorder recorder = build(file);

        assertEquals(Set.of("name", "count", "section"), new HashSet<>(recorder.added));
        final Map<String, Object> parsed = load(file);
        assertEquals("default-name", parsed.get("name"));
        assertEquals(Map.of("keep-alive", true, "tags", List.of("a")), parsed.get("section"));
        assertEquals("default-name", recorder.loaded.name);
        assertTrue(Files.readString(file).contains("# I commented every option out"));

        final String settled = Files.readString(file);
        final Recorder again = build(file);
        assertEquals(settled, Files.readString(file), "and the next run changes nothing");
        assertTrue(again.added.isEmpty());
    }

    @Test
    void aRootThatIsNotAMappingFailsLoudlyAndIsNotRewritten(@TempDir final Path dir) throws IOException {
        final Path file = dir.resolve("sample.yml");
        Files.writeString(file, "- one\n- two\n");

        assertThrows(UnsupportedOperationException.class, () -> build(file));

        assertEquals("- one\n- two\n", Files.readString(file));
    }

    @Test
    void aValueOfTheWrongTypeFailsTheLoadInsteadOfSilentlyDefaulting(@TempDir final Path dir) throws IOException {
        final Path file = dir.resolve("sample.yml");
        Files.writeString(file, "name: \"n\"\ncount: \"not a number\"\n");

        final Recorder recorder = new Recorder();
        assertThrows(RuntimeException.class,
            () -> ConfigurationProvider.buildSolidConfiguration(file, Sample::new, WRAP, recorder));

        assertEquals(0, recorder.finishes, "a failed load must not publish a configuration");
    }

    @Test
    void saveWritesLiveValuesIntoTheFileButLeavesUnknownKeysAndCommentsAlone(@TempDir final Path dir) throws IOException {
        final Path file = dir.resolve("sample.yml");
        Files.writeString(file, """
            # keep me
            name: "on-disk"
            count: 1
            legacy-flag: true
            section:
               keep-alive: true
               tags:
               - a
            """);
        final Sample live = new Sample();
        live.name = "live-name";
        live.section.keepAlive = false;

        live.save(file);

        final String text = Files.readString(file);
        final Map<String, Object> parsed = load(file);
        assertEquals("live-name", parsed.get("name"));
        assertEquals(3, parsed.get("count"), "every live value is written, including defaults");
        assertEquals(Boolean.TRUE, parsed.get("legacy-flag"), "save does not delete unknown keys");
        assertEquals(false, ((Map<?, ?>) parsed.get("section")).get("keep-alive"));
        assertTrue(text.contains("# keep me"), text);
    }

    @Test
    void saveRefusesAFileThatIsGone(@TempDir final Path dir) {
        assertThrows(IllegalArgumentException.class, () -> new Sample().save(dir.resolve("missing.yml")));
    }

    // ------------------------------------------------------------------ patchable (per-world) file

    @Test
    void aPatchOverridesBaseValuesAndIgnoresKeysTheBaseNoLongerHas(@TempDir final Path dir) throws IOException {
        final Path base = dir.resolve("base.yml");
        final Path patch = dir.resolve("patch.yml");
        build(base);
        Files.writeString(patch, """
            count: 9
            retired-key: 1
            section:
               keep-alive: false
            """);

        final Recorder recorder = new Recorder();
        ConfigurationProvider.buildPatchableConfiguration(patch, base, Sample::new, recorder);

        assertEquals(9, recorder.loaded.count);
        assertFalse(recorder.loaded.section.keepAlive);
        assertEquals("default-name", recorder.loaded.name, "untouched keys come from the base");
        assertEquals(List.of("a"), recorder.loaded.section.tags, "a nested override replaces only its own key");
        assertTrue(Files.readString(patch).contains("retired-key"), "the patch file is not rewritten");
    }

    @Test
    void anAbsentPatchIsCreatedEmptyWithOnlyTheHeader(@TempDir final Path dir) throws IOException {
        final Path base = dir.resolve("base.yml");
        final Path patch = dir.resolve("worlds").resolve("patch.yml");
        build(base);

        final Recorder recorder = new Recorder();
        ConfigurationProvider.buildPatchableConfiguration(patch, base, Sample::new, recorder, "Patch header");

        assertEquals("# Patch header\n\n", Files.readString(patch));
        assertEquals("default-name", recorder.loaded.name);
    }

    @Test
    void aPatchWithoutAnExistingBaseIsRefused(@TempDir final Path dir) {
        assertThrows(IllegalStateException.class, () -> ConfigurationProvider.buildPatchableConfiguration(
            dir.resolve("patch.yml"), dir.resolve("no-base.yml"), Sample::new, new Recorder()));
    }

    // ------------------------------------------------------------------ the real key sets

    @Test
    void theRealCanvasServerYmlKeySetUpgradesLosslesslyAndIdempotently(@TempDir final Path dir) throws IOException {
        final Path file = dir.resolve("canvas-server.yml");
        final RealRecorder<GlobalConfiguration> fresh = new RealRecorder<>();
        ConfigurationProvider.buildSolidConfiguration(file, GlobalConfiguration::new, WRAP, fresh);
        assertEquals(1, fresh.finishes);
        final Map<String, Object> shipped = load(file);

        // an "old" file: operator edits, two keys the current build added, two it has since retired
        final Map<String, Object> old = deepCopy(shipped);
        section(old, "region-scheduler").put("default-tick-rate", 30.0);
        section(old, "networking").put("player-info-send-interval", 123);
        section(old, "networking").remove("filter-velocity-packet");
        section(old, "networking").put("dead-option", true);
        old.put("legacy-section", new LinkedHashMap<>(Map.of("x", 1)));
        write(file, old);

        final RealRecorder<GlobalConfiguration> upgraded = new RealRecorder<>();
        ConfigurationProvider.buildSolidConfiguration(file, GlobalConfiguration::new, WRAP, upgraded);

        assertEquals(List.of("networking.filter-velocity-packet"), upgraded.added);
        assertEquals(Set.of("networking.dead-option", "legacy-section"), new HashSet<>(upgraded.removed));
        assertEquals(2, upgraded.removed.size());
        assertEquals(30.0F, upgraded.loaded.regionScheduler.defaultTickRate);
        assertEquals(123, upgraded.loaded.networking.playerInfoSendInterval);
        assertFalse(upgraded.loaded.networking.filterVelocityPacket, "re-added key takes the default");

        final Map<String, Object> after = load(file);
        assertFalse(after.containsKey("legacy-section"));
        assertFalse(section(after, "networking").containsKey("dead-option"));
        assertEquals(false, section(after, "networking").get("filter-velocity-packet"));
        // apart from the two values the operator changed, the file is exactly the shipped key set
        assertEquals(30.0, section(after, "region-scheduler").remove("default-tick-rate"));
        assertEquals(123, section(after, "networking").remove("player-info-send-interval"));
        section(shipped, "region-scheduler").remove("default-tick-rate");
        section(shipped, "networking").remove("player-info-send-interval");
        assertEquals(shipped, after);

        final String settled = Files.readString(file);
        final RealRecorder<GlobalConfiguration> again = new RealRecorder<>();
        ConfigurationProvider.buildSolidConfiguration(file, GlobalConfiguration::new, WRAP, again);
        assertEquals(settled, Files.readString(file), "second run is byte-identical");
        assertTrue(again.added.isEmpty() && again.removed.isEmpty());
        assertEquals(30.0F, again.loaded.regionScheduler.defaultTickRate);
    }

    @Test
    void theRealCanvasWorldsYmlKeySetRepairsADeletedSectionAndKeepsEdits(@TempDir final Path dir) throws IOException {
        final Path file = dir.resolve("canvas-worlds.yml");
        ConfigurationProvider.buildSolidConfiguration(file, WorldConfig::new, WRAP, new RealRecorder<WorldConfig>());
        final Map<String, Object> shipped = load(file);
        assertTrue(shipped.size() > 1, "world defaults have sections: " + shipped.keySet());

        final String firstKey = shipped.keySet().iterator().next();
        final Map<String, Object> old = deepCopy(shipped);
        old.remove(firstKey);
        old.put("retired-world-option", "x");
        write(file, old);

        final RealRecorder<WorldConfig> upgraded = new RealRecorder<>();
        ConfigurationProvider.buildSolidConfiguration(file, WorldConfig::new, WRAP, upgraded);

        assertTrue(upgraded.added.contains(firstKey), "removed top level key is re-added: " + upgraded.added);
        assertEquals(List.of("retired-world-option"), upgraded.removed);
        assertNotNull(upgraded.loaded);
        assertEquals(shipped, load(file));
    }

    private static final class RealRecorder<C extends Part> implements Resolver<C> {
        final List<String> added = new ArrayList<>();
        final List<String> removed = new ArrayList<>();
        C loaded;
        int finishes;

        @Override public void onDiffAdd(final String name) { this.added.add(name); }
        @Override public void onDiffRemove(final String name) { this.removed.add(name); }
        @Override public void onFinishLoad(final C instance) { this.loaded = instance; this.finishes++; }
    }

    // ------------------------------------------------------------------ helpers

    private static Map<String, Object> load(final Path file) throws IOException {
        final Map<String, Object> map = new Yaml().load(Files.readString(file));
        assertNotNull(map, "parsed " + file);
        return map;
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> section(final Map<String, Object> root, final String key) {
        final Object value = root.get(key);
        assertNotNull(value, "section " + key);
        return (Map<String, Object>) value;
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> deepCopy(final Map<String, Object> source) {
        final Map<String, Object> copy = new LinkedHashMap<>();
        for (final Map.Entry<String, Object> entry : source.entrySet()) {
            copy.put(entry.getKey(), entry.getValue() instanceof Map<?, ?> nested
                ? deepCopy((Map<String, Object>) nested)
                : entry.getValue());
        }
        return copy;
    }

    private static void write(final Path file, final Map<String, Object> content) throws IOException {
        final DumperOptions options = new DumperOptions();
        options.setDefaultFlowStyle(DumperOptions.FlowStyle.BLOCK);
        Files.writeString(file, new Yaml(options).dump(content));
    }
}
