/**
 * Small, dependency-light helpers shared across the SourbyCraft utility layer.
 *
 * <p>{@link dev.iyanz.sourbycraft.util.BarUtil} renders the {@code ▰▱} progress bars every panel
 * command uses; {@link dev.iyanz.sourbycraft.util.ContainerMemory} detects the cgroup/host memory
 * limit so the boot advisor and {@code /rambar} can surface a heap-vs-container mismatch;
 * {@link dev.iyanz.sourbycraft.util.GeoUtil} does offline IP-to-city lookup for {@code /ping};
 * {@link dev.iyanz.sourbycraft.util.SourbyLogger} is the shared {@code "SourbyCraft"} JUL logger
 * wrapper; {@link dev.iyanz.sourbycraft.util.VirtualExecutor} is the shared virtual-thread pool
 * used for off-region network/disk work ({@code /speedtest}, {@code /update}, {@code /ping} geoip).
 */
package dev.iyanz.sourbycraft.util;
