package dev.iyanz.sourbycraft.example;

import dev.iyanz.sourbycraft.api.FeatureUnavailableException;
import dev.iyanz.sourbycraft.api.SourbyAPI;
import dev.iyanz.sourbycraft.api.scheduler.AuroraScheduler;
import dev.iyanz.sourbycraft.api.world.AuroraWorlds;
import dev.iyanz.sourbycraft.api.world.AwfWorldSummary;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;
import org.bukkit.command.CommandSender;
import org.bukkit.plugin.Plugin;

/**
 * Aurora World Fabric worlds: {@code SourbyAPI.worlds()}, {@code listWorlds()} and {@code createInstance}.
 */
final class WorldsDemo {

    private final Plugin plugin;
    private final AuroraScheduler scheduler;
    private final AtomicInteger counter = new AtomicInteger();

    WorldsDemo(final Plugin plugin, final AuroraScheduler scheduler) {
        this.plugin = plugin;
        this.scheduler = scheduler;
    }

    /** Lists every world managed through AWF. {@code listWorlds()} touches no storage and may be called on any thread. */
    void list(final CommandSender sender) {
        final AuroraWorlds worlds;
        try {
            worlds = SourbyAPI.worlds(); // throws FeatureUnavailableException when the service did not start
        } catch (final FeatureUnavailableException unavailable) {
            sender.sendMessage("worlds: " + unavailable.getMessage());
            return;
        }
        final List<AwfWorldSummary> all = worlds.listWorlds();
        sender.sendMessage("AWF worlds: " + all.size());
        for (final AwfWorldSummary world : all) {
            sender.sendMessage(" - " + world.name() + (world.loaded() ? " [loaded]" : " [not loaded]")
                + world.template().map(template -> " template=" + template).orElse("")
                + (world.deleteOnUnload() ? " (instance, deleted when unloaded)" : ""));
        }
    }

    /**
     * Creates a disposable copy-on-write instance of {@code template}. The future completes on a storage thread, never on
     * the sender's region, so the reply is scheduled back to the sender.
     */
    void createInstance(final CommandSender sender, final String template) {
        final var found = SourbyAPI.findWorlds();
        if (found.isEmpty()) {
            sender.sendMessage("worlds: the AWF world service is not running");
            return;
        }
        final String name = "sbexample_" + this.counter.incrementAndGet();
        found.get().createInstance(template, name).whenComplete((world, failure) -> {
            final String message = failure == null
                ? "instance " + world.getName() + " is ready; unload it with the AWF commands and it deletes itself"
                : "instance failed: " + (failure.getCause() != null ? failure.getCause() : failure).getMessage();
            Replies.send(this.plugin, this.scheduler, sender, message);
        });
    }
}
