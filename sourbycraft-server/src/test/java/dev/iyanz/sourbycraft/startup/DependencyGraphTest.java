package dev.iyanz.sourbycraft.startup;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

/** Missing hard dependencies and cycles are found; soft ones only order. */
class DependencyGraphTest {

    private static PluginDescriptor p(final String name, final List<String> depend, final List<String> soft,
                                      final List<String> before) {
        return new PluginDescriptor(name, "1", "m", false, true, depend, soft, before);
    }

    @Test
    void ordersByEveryKindOfEdge() {
        final DependencyGraph.Report r = DependencyGraph.analyse(List.of(
            p("App", List.of("Lib"), List.of("Opt"), List.of()),
            p("Lib", List.of(), List.of(), List.of()),
            p("Opt", List.of(), List.of(), List.of()),
            p("Early", List.of(), List.of(), List.of("Lib"))));
        assertTrue(r.clean());
        final List<String> order = r.order();
        assertTrue(order.indexOf("Early") < order.indexOf("Lib"));
        assertTrue(order.indexOf("Lib") < order.indexOf("App"));
        assertTrue(order.indexOf("Opt") < order.indexOf("App"));
    }

    @Test
    void aMissingHardDependencyIsReportedAndASoftOneIsNot() {
        final DependencyGraph.Report r = DependencyGraph.analyse(List.of(
            p("App", List.of("Vault", "Lib"), List.of("Absent"), List.of()),
            p("Lib", List.of(), List.of(), List.of())));
        assertEquals(Map.of("App", List.of("Vault")), r.missing());
    }

    @Test
    void cyclesAreFoundAndPluginsDownstreamStillAppear() {
        final DependencyGraph.Report r = DependencyGraph.analyse(List.of(
            p("A", List.of("B"), List.of(), List.of()),
            p("B", List.of("C"), List.of(), List.of()),
            p("C", List.of("A"), List.of(), List.of()),
            p("Self", List.of("Self"), List.of(), List.of()),
            p("Downstream", List.of("A"), List.of(), List.of())));
        assertEquals(List.of(List.of("A", "B", "C"), List.of("Self")), r.cycles());
        assertTrue(r.order().contains("Downstream"));
        assertTrue(!r.order().contains("A"));
    }

    @Test
    void aLongChainDoesNotOverflow() {
        final java.util.List<PluginDescriptor> chain = new java.util.ArrayList<>();
        for (int i = 0; i < 20_000; i++) {
            chain.add(p("P" + i, i == 0 ? List.of() : List.of("P" + (i - 1)), List.of(), List.of()));
        }
        final DependencyGraph.Report r = DependencyGraph.analyse(chain);
        assertTrue(r.clean());
        assertEquals("P0", r.order().get(0));
    }
}
