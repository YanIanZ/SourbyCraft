/**
 * {@code /maxp} and the optional full-server join bypass (F1-6).
 *
 * <p>{@link dev.iyanz.sourbycraft.maxplayers.MaxPlayersConfig} persists + re-applies the operator-set
 * max-player slot count at boot so it survives a restart and wins over {@code server.properties}.
 * {@link dev.iyanz.sourbycraft.maxplayers.MaxPlayersBypass} is an opt-in (default off)
 * {@code PlayerLoginEvent} listener letting bypass-permission holders / ops join a full server.
 * Both were relocated out of the (now perf-engine-only) {@code perf} package on the Canvas
 * re-platform — they are kept utility features, not part of the deferred perf-engine.
 */
package dev.iyanz.sourbycraft.maxplayers;
