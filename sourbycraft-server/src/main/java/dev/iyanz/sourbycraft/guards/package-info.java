/**
 * SourbyCraft-owned replacements for guards the region-threading baseline inherited from Canvas and
 * Folia ({@code docs/architecture/inherited-guards.md}). Each class here holds the decision logic of
 * one guard so the Minecraft/Paper feature patch that routes to it stays a one-line hook:
 * {@link dev.iyanz.sourbycraft.guards.RegionRefusal} (refusal messages that name the supported
 * alternative), {@link dev.iyanz.sourbycraft.guards.UndrainedQueue} (the server queue nothing
 * drains), {@link dev.iyanz.sourbycraft.guards.PlayerCollisionTeam},
 * {@link dev.iyanz.sourbycraft.guards.ConversationInput},
 * {@link dev.iyanz.sourbycraft.guards.RegionStatistics},
 * {@link dev.iyanz.sourbycraft.guards.EndPlatformEvents}, the legacy synchronous events
 * {@link dev.iyanz.sourbycraft.guards.LegacyPreLogin} and {@link dev.iyanz.sourbycraft.guards.LegacyChat}
 * (over {@link dev.iyanz.sourbycraft.guards.BoundedHandoff}) and the server-wide tick state
 * {@link dev.iyanz.sourbycraft.guards.ServerTickControl}.
 */
package dev.iyanz.sourbycraft.guards;
