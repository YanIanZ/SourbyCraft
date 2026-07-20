/**
 * The {@code /tpsbar} + {@code /rambar} boss-bar HUD.
 *
 * <p>{@link dev.iyanz.sourbycraft.hud.HudBars} owns two shared, mutable Adventure boss bars (one
 * per metric) updated by a single global-region task, and the {@code QuitListener} inner class
 * that wires the admin auto-HUD on join and viewer cleanup on quit. See the class javadoc there
 * for why one shared bar per metric — not one per viewer — is the design.
 */
package dev.iyanz.sourbycraft.hud;
