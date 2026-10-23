package dev.iyanz.sourbycraft.startup;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.iyanz.sourbycraft.brand.PluginLoadDiagnostics;
import java.nio.file.Path;
import java.util.List;
import java.util.Set;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

/** A plugin the startup index read but the plugin manager never created is a load failure with a reason. */
class UnloadedPluginsTest {

    @AfterEach
    void reset() {
        PluginLoadDiagnostics.resetForTest();
    }

    private static PluginStartupIndex.Indexed jar(final String file, final PluginDescriptor d) {
        return new PluginStartupIndex.Indexed(Path.of("plugins", file), d, false, null, null, CompatibilityScan.EMPTY);
    }

    private static PluginDescriptor p(final String name, final List<String> depend, final List<String> provides) {
        return new PluginDescriptor(name, "1", "m", false, false, depend, List.of(), List.of(), provides);
    }

    @Test
    void theCommandNpcCase() {
        // Panel r48.2: CommandNPC (depend: [Citizens]) never loaded; WorldGuard loaded through FAWE's provides.
        final List<PluginStartupIndex.Indexed> index = List.of(
            jar("commandnpc-2.1.3.jar", p("CommandNPC", List.of("Citizens"), List.of())),
            jar("FastAsyncWorldEdit.jar", p("FastAsyncWorldEdit", List.of(), List.of("WorldEdit"))),
            jar("worldguard.jar", p("WorldGuard", List.of("WorldEdit"), List.of())),
            jar("some-library.jar", null));
        final DependencyGraph.Report graph = DependencyGraph.analyse(
            index.stream().map(PluginStartupIndex.Indexed::descriptor).filter(java.util.Objects::nonNull).toList());
        final Set<String> loaded = Set.of("FastAsyncWorldEdit", "WorldGuard");
        final List<UnloadedPlugins.Unloaded> out = UnloadedPlugins.find(index, graph, loaded::contains);
        assertEquals(List.of(new UnloadedPlugins.Unloaded("commandnpc-2.1.3.jar", "CommandNPC",
            "Missing hard dependency: Citizens; the plugin manager did not load it")), out);
    }

    @Test
    void aDependentOfAPluginThatDidNotLoadSaysWhich() {
        final List<PluginStartupIndex.Indexed> index = List.of(
            jar("b.jar", p("B", List.of("C"), List.of())),
            jar("a.jar", p("A", List.of("B"), List.of())),
            jar("x.jar", p("X", List.of(), List.of())));
        final DependencyGraph.Report graph = DependencyGraph.analyse(
            index.stream().map(PluginStartupIndex.Indexed::descriptor).toList());
        final List<UnloadedPlugins.Unloaded> out = UnloadedPlugins.find(index, graph, name -> false);
        assertEquals("Missing hard dependency: C; the plugin manager did not load it", out.get(0).reason());
        assertEquals("Hard dependency did not load: B; the plugin manager did not load it", out.get(1).reason());
        assertEquals("The plugin manager did not load it; the server log has the cause", out.get(2).reason());
    }

    @Test
    void aCycleAndAnInvalidDescriptorAreNamed() {
        final List<PluginStartupIndex.Indexed> index = List.of(
            jar("a.jar", p("A", List.of("B"), List.of())),
            jar("b.jar", p("B", List.of("A"), List.of())),
            new PluginStartupIndex.Indexed(Path.of("plugins", "broken.jar"), null, false,
                "unreadable: malformed plugin descriptor", null, CompatibilityScan.EMPTY),
            // A scan failure is not evidence that the plugin manager refused the jar.
            new PluginStartupIndex.Indexed(Path.of("plugins", "odd.jar"), null, false,
                "unreadable: zip END header not found", null, CompatibilityScan.EMPTY));
        final DependencyGraph.Report graph = DependencyGraph.analyse(List.of(
            index.get(0).descriptor(), index.get(1).descriptor()));
        final List<UnloadedPlugins.Unloaded> out = UnloadedPlugins.find(index, graph, name -> false);
        assertEquals(3, out.size());
        assertTrue(out.get(0).reason().startsWith("In a dependency cycle (A -> B)"));
        assertEquals(new UnloadedPlugins.Unloaded("broken.jar", null,
            "plugin.yml / paper-plugin.yml is not valid YAML; the plugin manager did not load it"), out.get(2));
    }

    @Test
    void loadedPluginsAreMatchedCaseInsensitivelyByTheCaller() {
        final List<PluginStartupIndex.Indexed> index = List.of(jar("v.jar", p("Vault", List.of(), List.of())));
        assertEquals(List.of(), UnloadedPlugins.find(index, DependencyGraph.analyse(List.of(index.get(0).descriptor())),
            "vault"::equalsIgnoreCase));
    }

    @Test
    void recordingSkipsAJarAlreadyRecordedAndCountsTheRest() {
        // The bridge records "x.jar"; the log capture records "plugins/x.jar". Either one wins.
        PluginLoadDiagnostics.record("refused.jar", "Refused", "bridge off");
        UnloadedPlugins.record(List.of(
            new UnloadedPlugins.Unloaded("refused.jar", "Refused", "other"),
            new UnloadedPlugins.Unloaded("commandnpc-2.1.3.jar", "CommandNPC", "Missing hard dependency: Citizens")));
        final List<PluginLoadDiagnostics.Entry> entries = PluginLoadDiagnostics.recent();
        assertEquals(2, entries.size());
        assertEquals("bridge off", entries.get(0).reason());
        assertEquals(new PluginLoadDiagnostics.Entry("commandnpc-2.1.3.jar", "Missing hard dependency: Citizens", "CommandNPC"),
            entries.get(1));
    }
}
