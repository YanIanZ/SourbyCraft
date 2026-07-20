/**
 * SourbyCraft's varied, config-driven message layer (F1-7).
 *
 * <p>{@link dev.iyanz.sourbycraft.lang.SourbyMessages} maps each message key (server-full kick,
 * MOTD, join, leave) to a list of MiniMessage variant strings in the unified TOML, picking one at
 * random per event so the same touchpoint reads differently each time.
 * {@link dev.iyanz.sourbycraft.lang.SourbyJoinLeaveListener} is the first consumer: it overrides
 * the vanilla join/leave broadcast with a {@link dev.iyanz.sourbycraft.lang.SourbyMessages} variant.
 */
package dev.iyanz.sourbycraft.lang;
