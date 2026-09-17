/**
 * The Aurora region system: SourbyCraft's own account of what a region is.
 *
 * <p>A region is a unit of gameplay ownership — a set of chunks whose entities and blocks may only
 * be mutated by the one thread that owns them. That definition is the engine's, not a backend's,
 * and this package states it without naming one. {@link dev.iyanz.sourbycraft.perf.RegionMetricsRegistry}
 * already tracked region identity, merges and retirement without a single Folia type; this package
 * gives that a home and a contract, and moves the last backend names behind one adapter.
 *
 * <p>Regions are why gameplay cannot be divided by subsystem. A region thread runs the whole tick
 * for what it owns, so "world load on one core and plugins on another" is not a thing a region
 * system can offer — what it can offer is honest accounting of how many regions there are, how each
 * is doing, and which execution lane carries them.
 */
package dev.iyanz.sourbycraft.execution.region;
