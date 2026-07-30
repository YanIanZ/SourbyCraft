/**
 * SourbyCraft's utility layer — the server-side branding, config and quality-of-life features kept
 * on the Canvas re-platform (feat/canvas-engine, PR #12) after the self-tuning perf-engine,
 * anti-xray raytrace reveal and proxy-forwarding/hardening-advisor layers were deferred.
 *
 * <p>This root package holds the two pieces every other sub-package depends on:
 * {@link dev.iyanz.sourbycraft.SourbyCraftConfig}, the single unified-TOML registry every
 * operator-facing setting is read from and written to, and
 * {@link dev.iyanz.sourbycraft.SourbyCraftColors}, the shared hex palette every branded command,
 * HUD bar and banner renders with.
 *
 * <p>Sub-packages: {@link dev.iyanz.sourbycraft.core} (the single boot hook),
 * {@link dev.iyanz.sourbycraft.bootstrap} (pre-server-classes bootstrap: library fetch, Auto-CDS,
 * the internal plugin handle), {@link dev.iyanz.sourbycraft.brand} (startup banner, GC advisor,
 * plugin-load diagnostics), {@link dev.iyanz.sourbycraft.command} (the {@code /tps} /
 * {@code /sys} / {@code /ver} / ... command suite), {@link dev.iyanz.sourbycraft.hud} (boss-bar
 * HUD), {@link dev.iyanz.sourbycraft.lang} (varied, config-driven messages),
 * {@link dev.iyanz.sourbycraft.maxplayers} ({@code /maxp} + the full-server bypass),
 * {@link dev.iyanz.sourbycraft.perf} (a display-only tier enum — not a perf engine), and
 * {@link dev.iyanz.sourbycraft.update} (the GitHub-release auto-updater).
 */
package dev.iyanz.sourbycraft;
