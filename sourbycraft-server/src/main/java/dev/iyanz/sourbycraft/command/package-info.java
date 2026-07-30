/**
 * SourbyCraft's branded command suite: {@code /tps}, {@code /mspt}, {@code /ping}, {@code /sys},
 * {@code /ver}, {@code /plugins}, {@code /speedtest}, {@code /maxp}, {@code /tpsbar}/{@code /rambar},
 * {@code /update} and {@code /sourbycraft}. Every command extends the legacy Bukkit
 * {@link org.bukkit.command.Command} directly (not a plugin-owned {@code CommandExecutor}) so it
 * can be registered straight into the server's command map by
 * {@link dev.iyanz.sourbycraft.command.SourbyCraftCommands#registerAll()}, which claims each bare
 * name AFTER Paper's own built-ins register so {@code /tps} etc. resolve to the SourbyCraft styled
 * version rather than Paper's.
 *
 * <p>{@link dev.iyanz.sourbycraft.command.SourbyReply} is the shared Folia-safe helper commands
 * use to deliver a reply produced off-thread (virtual-thread network calls) back to the invoking
 * {@link org.bukkit.command.CommandSender} on the correct thread.
 */
package dev.iyanz.sourbycraft.command;
