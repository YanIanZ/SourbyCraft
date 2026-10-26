/**
 * The {@code /tpsbar}, {@code /rambar} and {@code /perfbar} HUD (boss bar or actionbar per player).
 *
 * <p>{@link dev.iyanz.sourbycraft.hud.HudBars} owns three shared, mutable Adventure boss bars (TPS,
 * RAM, PERF) updated by a single global-region task, the per-player actionbar delivery, and the
 * {@code QuitListener} inner class that wires the admin auto-HUD on join and viewer cleanup on quit.
 * See the class javadoc there for why one shared bar per metric — not one per viewer — is the design.
 */
package dev.iyanz.sourbycraft.hud;
