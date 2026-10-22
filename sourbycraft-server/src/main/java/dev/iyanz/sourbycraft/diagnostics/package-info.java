/**
 * Evidence that survives a hung or killed server. {@link dev.iyanz.sourbycraft.diagnostics.StallRecorder}
 * watches the global tick and every active region from its own daemon thread and, when one has completed
 * no tick for a configured time, writes thread dumps with lock owners and memory figures to
 * {@code logs/stalls/}. It exists for hosted panels where nobody has a shell to run {@code jcmd}.
 * Nothing here runs on a tick thread; the only coupling to the tick path is two volatile markers read
 * through {@code dev.iyanz.sourbycraft.perf.RegionTickMetrics}.
 */
package dev.iyanz.sourbycraft.diagnostics;
