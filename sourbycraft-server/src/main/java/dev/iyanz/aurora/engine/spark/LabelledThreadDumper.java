package dev.iyanz.aurora.engine.spark;

import dev.iyanz.aurora.engine.threadedregions.profiler.RegionProfiler;
import java.lang.management.ThreadInfo;
import java.lang.management.ThreadMXBean;
import me.lucko.spark.paper.common.sampler.ThreadDumper;
import me.lucko.spark.paper.proto.SparkSamplerProtos;

/**
 * Spark's default thread dumper for this server: the region scheduler's threads, selected exactly as
 * {@code RegionThreadDumper} selects them, but described truthfully in the report.
 *
 * <p>{@code RegionThreadDumper} reports {@code SPECIFIC} with no thread ids, which tells a reader of
 * the report nothing about what was sampled. This one reports {@code REGEX} with the pattern of
 * {@link SparkThreadClass#REGION_TICK} when the server is not pinned to a region, and
 * {@code SPECIFIC} with the pinned thread's id during a region profile. It cannot say whether that
 * thread was running the global tick or a region: they are the same thread.</p>
 */
public final class LabelledThreadDumper implements ThreadDumper {

    private final ThreadDumper selection;

    public LabelledThreadDumper(final ThreadDumper selection) {
        this.selection = selection;
    }

    @Override
    public ThreadInfo[] dumpThreads(final ThreadMXBean threadBean) {
        return this.selection.dumpThreads(threadBean);
    }

    @Override
    public boolean isThreadIncluded(final long threadId, final String threadName) {
        return this.selection.isThreadIncluded(threadId, threadName);
    }

    @Override
    public SparkSamplerProtos.SamplerMetadata.ThreadDumper getMetadata() {
        final SparkSamplerProtos.SamplerMetadata.ThreadDumper.Builder builder = SparkSamplerProtos.SamplerMetadata.ThreadDumper.newBuilder();
        final RegionProfiler.ProfilingState state = RegionProfiler.STATE.get();
        final Thread pinned = state == null || state.threadRunner() == null ? null : state.threadRunner().getRunnerThread();
        if (pinned != null) {
            return builder.setType(SparkSamplerProtos.SamplerMetadata.ThreadDumper.Type.SPECIFIC).addIds(pinned.threadId()).build();
        }
        return builder.setType(SparkSamplerProtos.SamplerMetadata.ThreadDumper.Type.REGEX)
            .addAllPatterns(SparkThreadClass.REGION_TICK.patterns()).build();
    }
}
