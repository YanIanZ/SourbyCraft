/**
 * OS-level swap management (r40).
 *
 * <p>{@link dev.iyanz.sourbycraft.swap.AutoSwap} optionally creates + enables a Linux swapfile on
 * boot when the host has none, giving the OS spill headroom before a hard OOM-kill. Config-gated,
 * default OFF; requires root/{@code CAP_SYS_ADMIN} and skips gracefully (one clear log line, never
 * a crash) when that is unavailable — the common case on unprivileged panels/containers. See its
 * class javadoc for the full privilege story. Independent of {@link dev.iyanz.sourbycraft.perf.SmartSwap}
 * (which reclaims HEAP, not OS swap) — the two are complementary, not the same mechanism.
 */
package dev.iyanz.sourbycraft.swap;
