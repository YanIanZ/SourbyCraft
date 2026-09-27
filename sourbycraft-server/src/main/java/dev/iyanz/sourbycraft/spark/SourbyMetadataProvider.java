package dev.iyanz.sourbycraft.spark;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import java.util.Map;
import java.util.function.Consumer;
import me.lucko.spark.paper.common.platform.MetadataProvider;

/**
 * Aurora runtime metadata attached to every spark report, under one {@code sourbycraft} key.
 *
 * <p>Cheap by construction: every value is an in-memory read of state the runtime already keeps
 * (build identity, lifecycle state, bridge and governor counters, switches, the startup profile).
 * Nothing here touches disk, the network, a region or a lock held by gameplay. No configuration
 * values that could carry secrets are included; the config files are reported separately, with
 * secret filtering, by {@link SourbyServerConfigProvider}.</p>
 *
 * <p>Each section is read on its own; one that throws is reported as {@code "unavailable"} rather
 * than failing the report.</p>
 */
public final class SourbyMetadataProvider implements MetadataProvider {

    @Override
    public Map<String, JsonElement> get() {
        return Map.of("sourbycraft", build());
    }

    static JsonObject build() {
        final JsonObject root = new JsonObject();
        section(root, "build", o -> {
            final var info = dev.iyanz.sourbycraft.brand.BuildInfo.load();
            o.addProperty("identity", info.releaseIdentity());
            o.addProperty("build", info.build());
            o.addProperty("codename", info.codename());
            o.addProperty("minecraft", info.mcVersion());
            o.addProperty("engine", info.engineName());
        });
        section(root, "runtime", o -> {
            o.addProperty("state", dev.iyanz.sourbycraft.core.AuroraRuntime.state().name());
            o.addProperty("processors", Runtime.getRuntime().availableProcessors());
        });
        section(root, "bridge", o -> {
            final var mode = dev.iyanz.sourbycraft.bridge.AuroraBridge.modeIfStarted();
            o.addProperty("mode", mode == null ? "not-started" : mode.name().toLowerCase(java.util.Locale.ROOT));
            final var stats = dev.iyanz.sourbycraft.bridge.AuroraBridge.allStats();
            o.addProperty("bridgedPlugins", stats.size());
            o.addProperty("quarantined", stats.stream().filter(s -> s.quarantined()).count());
            o.addProperty("fatalViolations", stats.stream().mapToLong(s -> s.fatalViolations()).sum());
        });
        section(root, "governor", o -> {
            for (final var lane : dev.iyanz.sourbycraft.execution.ResourceGovernor.GLOBAL.stats()) {
                final JsonObject l = new JsonObject();
                l.addProperty("threads", lane.threads());
                l.addProperty("queue", lane.queueCapacity());
                l.addProperty("rejected", lane.rejected());
                o.add(lane.name(), l);
            }
        });
        section(root, "switches", o -> {
            final var aurora = dev.iyanz.sourbycraft.SourbyCraftConfig.aurora();
            o.addProperty("asyncPathfinding", aurora.entity().asyncPathfinding());
            o.addProperty("laneSampling", aurora.diagnostics().laneSampling());
            o.addProperty("networkCounters", aurora.network().counters());
            o.addProperty("cpuCores", aurora.cpu().cores());
        });
        section(root, "startup", o -> {
            final var profile = dev.iyanz.sourbycraft.startup.StartupTimeline.last();
            if (profile == null) {
                o.addProperty("state", "not-ready");
                return;
            }
            o.addProperty("totalMillis", profile.totalMillis());
            o.addProperty("startClass", profile.startClass());
        });
        return root;
    }

    private static void section(final JsonObject root, final String name, final Consumer<JsonObject> fill) {
        final JsonObject section = new JsonObject();
        try {
            fill.accept(section);
            root.add(name, section);
        } catch (final Throwable unavailable) {
            root.addProperty(name, "unavailable");
        }
    }
}
