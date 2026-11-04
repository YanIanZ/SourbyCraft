package dev.iyanz.sourbycraft.example;

import dev.iyanz.sourbycraft.api.SourbyAPI;
import dev.iyanz.sourbycraft.api.profile.AuroraProfiles;
import dev.iyanz.sourbycraft.api.profile.ProfileDetails;
import dev.iyanz.sourbycraft.api.profile.ProfileTypeDefinition;
import dev.iyanz.sourbycraft.api.profile.ProfileTypeRegistry;
import dev.iyanz.sourbycraft.api.scheduler.AuroraScheduler;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;
import org.bukkit.plugin.Plugin;

/**
 * AWF MultiProfile reads: {@code profiles().isEnabled()}, the active profile, {@code getProfileDetails} and the effective
 * type id. MultiProfile is off by default, so every path first asks whether it is there.
 */
final class ProfilesDemo {

    private final Plugin plugin;
    private final AuroraScheduler scheduler;

    ProfilesDemo(final Plugin plugin, final AuroraScheduler scheduler) {
        this.plugin = plugin;
        this.scheduler = scheduler;
    }

    /** Describes the sender's active profile, asynchronously. */
    void describe(final CommandSender sender) {
        if (!(sender instanceof Player player)) {
            sender.sendMessage("profile: run this as a player");
            return;
        }
        // findProfiles() is empty while aurora.awf.multiprofile.enabled is false; isEnabled() is the service's own flag.
        final var found = SourbyAPI.findProfiles();
        if (found.isEmpty() || !found.get().isEnabled()) {
            sender.sendMessage("profile: AWF MultiProfile is off (" + SourbyAPI.MULTIPROFILE_ENABLED_KEY + ")");
            return;
        }
        final AuroraProfiles profiles = found.get();
        // Both calls complete on a storage thread. Never join() them here: this is a region thread.
        profiles.getActiveProfile(player.getUniqueId())
            .thenCompose(active -> active.isPresent()
                ? profiles.getProfileDetails(active.get().id())
                : java.util.concurrent.CompletableFuture.<java.util.Optional<ProfileDetails>>completedFuture(java.util.Optional.empty()))
            .whenComplete((details, failure) -> {
                final String message;
                if (failure != null) {
                    message = "profile lookup failed: " + (failure.getCause() != null ? failure.getCause() : failure).getMessage();
                } else if (details.isEmpty()) {
                    message = "no active profile";
                } else {
                    final ProfileDetails d = details.get();
                    // typeId() is the effective type: the built-in enum name or the registered id stored in metadata.
                    message = "profile " + d.name() + " (" + d.id() + "), type " + d.typeId();
                }
                Replies.send(this.plugin, this.scheduler, sender, message);
            });
    }

    /** Lists the registered types and who owns them. {@code definitions()} and {@code owner} do no I/O. */
    void describeTypes(final CommandSender sender) {
        final var found = SourbyAPI.findProfileTypes();
        if (found.isEmpty()) {
            sender.sendMessage("types: the profile type registry exists only while AWF MultiProfile runs");
            return;
        }
        final ProfileTypeRegistry registry = found.get();
        for (final ProfileTypeDefinition type : registry.definitions()) {
            sender.sendMessage(" - " + type.id() + " \"" + type.displayName() + "\" owner=" + registry.owner(type.id()).orElse("built-in")
                + " bank.deposit=" + registry.allows(type.id(), ProfileTypeRegistry.BANK_DEPOSIT)
                + " island-bank=" + registry.allows(type.id(), IronmanType.ISLAND_BANK));
        }
    }
}
