/**
 * SourbyCraft-owned replacements for guards the region-threading baseline inherited from Canvas and
 * Folia ({@code docs/architecture/inherited-guards.md}). Each class here holds the decision logic of
 * one guard so the Minecraft/Paper feature patch that routes to it stays a one-line hook:
 * {@link dev.iyanz.sourbycraft.guards.RegionRefusal} (refusal messages that name the supported
 * alternative), {@link dev.iyanz.sourbycraft.guards.UndrainedQueue} (the server queue nothing
 * drains), {@link dev.iyanz.sourbycraft.guards.PlayerCollisionTeam},
 * {@link dev.iyanz.sourbycraft.guards.ConversationInput},
 * {@link dev.iyanz.sourbycraft.guards.RegionStatistics} and
 * {@link dev.iyanz.sourbycraft.guards.EndPlatformEvents}.
 */
package dev.iyanz.sourbycraft.guards;
