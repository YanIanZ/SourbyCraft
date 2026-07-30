/**
 * SourbyCraft's single boot-hook package.
 *
 * <p>{@link dev.iyanz.sourbycraft.core.SourbyCraftBootstrap#init()} is called directly from a
 * hand-authored {@code minecraft-patch} to {@code DedicatedServer#initServer} — right after
 * Paper's own command registration and before plugins are enabled — and wires up every kept
 * utility-layer feature (config, branding, commands, messages, max-players, the auto-updater).
 * Replaces the archived Folia build's Luminol-triggered {@code PerfEngineBootstrap}, which also
 * wired the now-deferred self-tuning perf-engine, anti-xray and proxy layers.
 */
package dev.iyanz.sourbycraft.core;
