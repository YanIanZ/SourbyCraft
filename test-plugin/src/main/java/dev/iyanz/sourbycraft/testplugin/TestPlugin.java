package dev.iyanz.sourbycraft.testplugin;

import dev.iyanz.sourbycraft.api.metrics.MetricState;
import dev.iyanz.sourbycraft.api.metrics.PerformanceSnapshot;
import dev.iyanz.sourbycraft.api.metrics.RuntimeMetrics;
import dev.iyanz.sourbycraft.api.metrics.SourbyMetrics;
import io.papermc.paper.plugin.lifecycle.event.types.LifecycleEvents;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import org.bukkit.Bukkit;
import org.bukkit.event.Listener;
import org.bukkit.plugin.java.JavaPlugin;

public class TestPlugin extends JavaPlugin implements Listener {

    /** Ticks between two looks at the snapshot while waiting for the first real sample. */
    private static final long SAMPLE_RETRY_TICKS = 20L;
    /** At one try a second, how long a boot may take to publish a real sample before CI fails. */
    private static final int SAMPLE_ATTEMPTS = 60;

    @Override
    public void onLoad() {
        this.requireMetrics("SOURBY_METRICS_ONLOAD_OK");
    }

    @Override
    public void onEnable() {
        this.requireMetrics("SOURBY_METRICS_ONENABLE_OK");
        Bukkit.getPluginManager().registerEvents(this, this);
        // Test-server probes for the owned Entity#teleport and PlayerSpawnLocationEvent; CI boots without them.
        final boolean probes = Boolean.getBoolean("sourby.testplugin.probes");
        final TeleportProbe teleportProbe = probes ? new TeleportProbe(this) : null;
        UnloadFixtureProbe unloadProbe = null;
        final LegacyEventsProbe legacyProbe = probes ? new LegacyEventsProbe(this) : null;
        if (probes) {
            Bukkit.getPluginManager().registerEvents(legacyProbe, this);
            Bukkit.getPluginManager().registerEvents(teleportProbe, this);
            Bukkit.getPluginManager().registerEvents(new SpawnLocationProbe(), this);
            Bukkit.getPluginManager().registerEvents(new PearlTeleportProbe(), this);
            // The unload fixture runs once after startup (CI greps its markers) and on /unloadprobe.
            unloadProbe = new UnloadFixtureProbe(this);
            Bukkit.getPluginManager().registerEvents(unloadProbe, this);
        }
        final UnloadFixtureProbe finalUnloadProbe = unloadProbe;
        // A Paper plugin must register commands through the lifecycle COMMANDS event.
        // JavaPlugin#getCommand throws UnsupportedOperationException during startup here.
        this.getLifecycleManager().registerEventHandler(LifecycleEvents.COMMANDS, event -> {
            final var registrar = event.registrar();
            registrar.register("massspawn", "Spawn many entities at a position, batched across ticks.",
                new StressCommands(this, "massspawn"));
            registrar.register("flyspeed", "Set a player's fly speed for extreme-range testing.",
                new StressCommands(this, "flyspeed"));
            if (finalUnloadProbe != null) {
                registrar.register("unloadprobe", "Queue region/entity tasks in a temporary world, unload it, report (test server).", finalUnloadProbe);
            }
            if (probes) {
                registrar.register("stallprobe", "Block the global tick or a region on purpose (test server only).", new StallProbe(this));
            }
            if (legacyProbe != null) {
                registrar.register("tickprobe", "Freeze, step, sprint and rate through ServerTickManager (test server).", legacyProbe);
            }
            if (teleportProbe != null) {
                registrar.register("tpprobe", "Drive Entity#teleport through each owned route (test server).", teleportProbe);
            }
            this.getLogger().info("SOURBY_STRESS_COMMANDS_OK");
        });
        this.awaitSampledMetrics(1);
    }

    /**
     * The service is there and answers. Its first snapshot may still be the WARMING placeholder
     * (NaN target, -1 heap): the collector samples once a second from before plugin load, but
     * nothing says it has run by now. The values are checked by {@link #awaitSampledMetrics}.
     */
    private void requireMetrics(final String marker) {
        final SourbyMetrics metrics = this.getServer().getServicesManager().load(SourbyMetrics.class);
        if (metrics == null) {
            throw new IllegalStateException("SourbyMetrics service unavailable");
        }
        final PerformanceSnapshot snapshot = metrics.snapshot();
        if (snapshot == null || snapshot.freshness() == null || snapshot.runtime() == null) {
            throw new IllegalStateException("SourbyMetrics snapshot incomplete: " + snapshot);
        }
        this.getLogger().info(marker + " state=" + snapshot.freshness().state() + " sequence=" + snapshot.sequence());
    }

    /**
     * Logs {@code SOURBY_METRICS_SAMPLED_OK} once the snapshot carries real values -- a finite
     * target TPS, a heap, processors, a sample time -- or {@code SOURBY_METRICS_SAMPLED_FAILED}
     * with what was wrong after {@link #SAMPLE_ATTEMPTS}. CI waits for the first and fails on the
     * second: a marker that only proved the service was non-null proved nothing about the values
     * (OBS-9).
     */
    private void awaitSampledMetrics(final int attempt) {
        Bukkit.getGlobalRegionScheduler().runDelayed(this, task -> {
            final SourbyMetrics metrics = this.getServer().getServicesManager().load(SourbyMetrics.class);
            final PerformanceSnapshot snapshot = metrics == null ? null : metrics.snapshot();
            final List<String> wrong = new ArrayList<>();
            if (snapshot == null) {
                wrong.add("snapshot=null");
            } else {
                final RuntimeMetrics runtime = snapshot.runtime();
                if (!(Double.isFinite(snapshot.targetTps()) && snapshot.targetTps() > 0.0)) wrong.add("targetTps=" + snapshot.targetTps());
                if (snapshot.sampledAtEpochMillis() <= 0L) wrong.add("sampledAtEpochMillis=" + snapshot.sampledAtEpochMillis());
                if (snapshot.freshness().state() == MetricState.UNAVAILABLE) {
                    wrong.add("state=UNAVAILABLE (" + snapshot.freshness().diagnostic() + ")");
                }
                if (runtime.heapMaxBytes() <= 0L) wrong.add("heapMaxBytes=" + runtime.heapMaxBytes());
                if (runtime.heapUsedBytes() <= 0L) wrong.add("heapUsedBytes=" + runtime.heapUsedBytes());
                if (runtime.availableProcessors() < 1) wrong.add("availableProcessors=" + runtime.availableProcessors());
                if (runtime.uptimeMillis() <= 0L) wrong.add("uptimeMillis=" + runtime.uptimeMillis());
            }
            if (wrong.isEmpty()) {
                final RuntimeMetrics runtime = snapshot.runtime();
                this.getLogger().info(String.format(Locale.ROOT,
                    "SOURBY_METRICS_SAMPLED_OK targetTps=%.2f state=%s sequence=%d heapUsed=%d heapMax=%d processors=%d uptimeMs=%d attempt=%d",
                    snapshot.targetTps(), snapshot.freshness().state(), snapshot.sequence(), runtime.heapUsedBytes(),
                    runtime.heapMaxBytes(), runtime.availableProcessors(), runtime.uptimeMillis(), attempt));
                return;
            }
            if (attempt >= SAMPLE_ATTEMPTS) {
                this.getLogger().severe("SOURBY_METRICS_SAMPLED_FAILED after " + attempt + " attempts: " + wrong);
                return;
            }
            this.awaitSampledMetrics(attempt + 1);
        }, SAMPLE_RETRY_TICKS);
    }
}
