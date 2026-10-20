package dev.iyanz.aurora.engine.spark;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import dev.iyanz.aurora.engine.threadedregions.profiler.RegionProfiler;
import dev.iyanz.aurora.engine.threadedregions.scheduler.AffinitySchedulerThreadPool;
import java.util.List;
import java.util.Map;
import java.util.Set;
import me.lucko.spark.paper.common.platform.MetadataProvider;
import me.lucko.spark.paper.common.sampler.ThreadDumper;
import me.lucko.spark.paper.proto.SparkSamplerProtos;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * OBS-14 engine spark: the report-side labelling, {@link ThreadCensusMetadataProvider} (what threads
 * exist, by class, and the pool labels the viewer will show) and {@link LabelledThreadDumper} (what
 * the default dumper says it sampled). Spark lets a platform plugin choose the default dumper and add
 * report metadata; it does not let it supply a thread grouper, so these two are the supported surface.
 */
class ThreadCensusMetadataProviderTest {

    private RegionProfiler.ProfilingState previousState;

    @BeforeEach
    void noRegionProfileRuns() {
        this.previousState = RegionProfiler.STATE.getAndSet(null);
    }

    @AfterEach
    void restoreProfilingState() {
        RegionProfiler.STATE.set(this.previousState);
    }

    private static JsonObject censusOf(final String... names) {
        return new ThreadCensusMetadataProvider(() -> List.of(names)).get().get(ThreadCensusMetadataProvider.KEY).getAsJsonObject();
    }

    private static Set<String> poolsOf(final JsonObject entry) {
        final JsonArray array = entry.getAsJsonArray("pools");
        final java.util.TreeSet<String> out = new java.util.TreeSet<>();
        array.forEach(element -> out.add(element.getAsString()));
        return out;
    }

    @Test
    void theCensusCountsThreadsAndListsPoolLabelsPerClass() {
        final JsonObject classes = censusOf(
            "Folia Region Scheduler Thread #0", "Folia Region Scheduler Thread #1", "Folia Region Scheduler Thread #2",
            "Paper Common Worker #0", "Paper Common Worker #1", "Paper I/O Worker #0",
            "SourbyCraft-Storage-redis-lease", "SourbyCraft-BridgeIO-1", "SourbyCraft-AsyncPath-1", "SourbyCraft-AsyncPath-2",
            "SourbyCraft-AWF-Maintenance", "Netty Epoll IO #0", "Netty Epoll IO #1", "main", "Server thread"
        ).getAsJsonObject("classes");

        assertEquals(3, classes.getAsJsonObject("Region and global tick").get("threads").getAsInt());
        assertEquals(Set.of("Folia Region Scheduler Thread"), poolsOf(classes.getAsJsonObject("Region and global tick")));
        assertEquals(2, classes.getAsJsonObject("Chunk workers").get("threads").getAsInt());
        assertEquals(Set.of("Paper Common Worker"), poolsOf(classes.getAsJsonObject("Chunk workers")));
        assertEquals(Set.of("Paper I/O Worker"), poolsOf(classes.getAsJsonObject("Chunk I/O")));
        assertEquals(Set.of("SourbyCraft-BridgeIO", "SourbyCraft-Storage-redis-lease"), poolsOf(classes.getAsJsonObject("Sourby lanes")));
        assertEquals(2, classes.getAsJsonObject("Async path pool").get("threads").getAsInt());
        assertEquals(Set.of("SourbyCraft-AWF-Maintenance"), poolsOf(classes.getAsJsonObject("AWF maintenance")));
        assertEquals(Set.of("Netty Epoll IO"), poolsOf(classes.getAsJsonObject("Network")));
        assertEquals(2, classes.getAsJsonObject("Other").get("threads").getAsInt());
        assertEquals(Set.of("Region and global tick", "Chunk workers", "Chunk I/O", "Sourby lanes", "Async path pool",
            "AWF maintenance", "Network", "Other"), classes.keySet());
    }

    @Test
    void classesWithoutThreadsAreLeftOut() {
        final JsonObject classes = censusOf("main").getAsJsonObject("classes");
        assertEquals(Set.of("Other"), classes.keySet());
        assertTrue(censusOf().getAsJsonObject("classes").keySet().isEmpty());
    }

    @Test
    void theReportStatesWhatSparkCannotSeparate() {
        final String note = censusOf("main").get("note").getAsString();
        assertTrue(note.contains("global tick") && note.contains("same"), note);
        assertTrue(note.contains("virtual"), note);
    }

    @Test
    @org.junit.jupiter.api.Timeout(60)
    void theDefaultCensusReadsTheLiveThreads() throws Exception {
        final java.util.concurrent.CountDownLatch release = new java.util.concurrent.CountDownLatch(1);
        final Thread thread = Thread.ofPlatform().daemon().name("SourbyCraft-AsyncPath-7777").start(() -> {
            try {
                release.await(60, java.util.concurrent.TimeUnit.SECONDS);
            } catch (final InterruptedException ignored) {
                // released
            }
        });
        try {
            final JsonObject classes = new ThreadCensusMetadataProvider().get().get(ThreadCensusMetadataProvider.KEY)
                .getAsJsonObject().getAsJsonObject("classes");

            assertTrue(classes.has("Async path pool"), classes.toString());
            assertTrue(poolsOf(classes.getAsJsonObject("Async path pool")).contains("SourbyCraft-AsyncPath"));
        } finally {
            release.countDown();
            thread.join(30_000L);
        }
    }

    @Test
    void combineKeepsEveryKeyAndTheFirstProviderWinsAClash() {
        final MetadataProvider first = () -> Map.<String, JsonElement>of("a", new com.google.gson.JsonPrimitive("first-a"), "clash", new com.google.gson.JsonPrimitive("first"));
        final MetadataProvider second = () -> Map.<String, JsonElement>of("b", new com.google.gson.JsonPrimitive("second-b"), "clash", new com.google.gson.JsonPrimitive("second"));

        final Map<String, JsonElement> merged = ThreadCensusMetadataProvider.combine(first, second).get();

        assertEquals(Set.of("a", "b", "clash"), merged.keySet());
        assertEquals("first", merged.get("clash").getAsString());
        assertEquals("second-b", merged.get("b").getAsString());
        assertTrue(ThreadCensusMetadataProvider.combine().get().isEmpty());
    }

    // ---- LabelledThreadDumper

    private static ThreadDumper selection() {
        final ThreadDumper selection = mock(ThreadDumper.class);
        when(selection.isThreadIncluded(1L, "included")).thenReturn(true);
        return selection;
    }

    @Test
    void theLabelledDumperSelectsExactlyWhatItWraps() {
        final LabelledThreadDumper dumper = new LabelledThreadDumper(selection());
        assertTrue(dumper.isThreadIncluded(1L, "included"));
        assertFalse(dumper.isThreadIncluded(2L, "other"));
    }

    @Test
    void outsideARegionProfileItReportsTheRegionTickPatternAsRegex() {
        final SparkSamplerProtos.SamplerMetadata.ThreadDumper metadata = new LabelledThreadDumper(selection()).getMetadata();

        assertEquals(SparkSamplerProtos.SamplerMetadata.ThreadDumper.Type.REGEX, metadata.getType());
        assertEquals(SparkThreadClass.REGION_TICK.patterns(), metadata.getPatternsList());
        assertEquals(0, metadata.getIdsCount());
    }

    @Test
    void duringARegionProfileItReportsThePinnedThreadsId() {
        final Thread pinned = new Thread(() -> { }, "Folia Region Scheduler Thread #3");
        final AffinitySchedulerThreadPool.TickThreadRunner runner = mock(AffinitySchedulerThreadPool.TickThreadRunner.class);
        when(runner.getRunnerThread()).thenReturn(pinned);
        RegionProfiler.STATE.set(new RegionProfiler.ProfilingState(null, null, runner));

        final SparkSamplerProtos.SamplerMetadata.ThreadDumper metadata = new LabelledThreadDumper(selection()).getMetadata();

        assertEquals(SparkSamplerProtos.SamplerMetadata.ThreadDumper.Type.SPECIFIC, metadata.getType());
        assertEquals(List.of(pinned.threadId()), metadata.getIdsList());
        assertEquals(0, metadata.getPatternsCount());
    }
}
