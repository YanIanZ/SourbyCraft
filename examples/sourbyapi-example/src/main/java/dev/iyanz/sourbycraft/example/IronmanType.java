package dev.iyanz.sourbycraft.example;

import dev.iyanz.sourbycraft.api.SourbyAPI;
import dev.iyanz.sourbycraft.api.profile.ProfileTypeDefinition;
import dev.iyanz.sourbycraft.api.profile.ProfileTypeRegistry;
import java.util.logging.Level;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.server.ServiceRegisterEvent;
import org.bukkit.plugin.Plugin;

/**
 * Registers an {@code ironman} profile type that denies the shared bank. The built-in {@code ironman} denies only
 * {@code trade}, {@code auction} and {@code bazaar}; this definition replaces it (it does not merge) and adds
 * {@code bank.deposit}, {@code bank.withdraw} and the consumer-owned {@code island-bank}.
 *
 * <p>The engine enforces {@code bank.deposit} and {@code bank.withdraw} itself ({@code ACTION_FORBIDDEN_BY_TYPE}).
 * {@code island-bank} is an id only this plugin knows; the server never checks it, the plugin asks
 * {@code registry.allows(typeId, "island-bank")} before opening an island bank.</p>
 */
final class IronmanType {

    /** A consumer-owned action id: free string {@code [a-z0-9][a-z0-9_.:-]{0,63}}. */
    static final String ISLAND_BANK = "island-bank";

    private IronmanType() {
    }

    static ProfileTypeDefinition definition() {
        return ProfileTypeDefinition.builder("ironman")
            .displayName("Ironman")
            .description("No trading, auction house, bazaar or bank.")
            .deny("trade").deny("auction").deny("bazaar")
            .deny(ProfileTypeRegistry.BANK_DEPOSIT)
            .deny(ProfileTypeRegistry.BANK_WITHDRAW)
            .deny(ISLAND_BANK)
            .build();
    }

    /**
     * Registers when the registry exists. {@code register} puts the definition in force immediately and writes it to the
     * store on the registry's own thread; it does no I/O on the caller's thread, so any thread may call it.
     */
    static void registerIfAvailable(final Plugin plugin) {
        SourbyAPI.findProfileTypes().ifPresentOrElse(registry -> {
            try {
                registry.register(plugin, definition());
                plugin.getLogger().info("registered the ironman profile type");
            } catch (final RuntimeException refused) {
                // For example another plugin owns the id, or a built-in override is not allowed
                // (types.registry.allow-override-builtins). Never swallow this silently: the deny would not be in force.
                plugin.getLogger().log(Level.WARNING, "ironman type not registered", refused);
            }
        }, () -> plugin.getLogger().info("AWF MultiProfile is off; the ironman type is registered when it starts"));
    }

    /** Registers again when the registry service appears after this plugin was enabled. */
    static final class Registration implements Listener {

        private final Plugin plugin;

        Registration(final Plugin plugin) {
            this.plugin = plugin;
        }

        @EventHandler
        public void onService(final ServiceRegisterEvent event) {
            if (event.getProvider().getService() == ProfileTypeRegistry.class) {
                registerIfAvailable(this.plugin);
            }
        }
    }
}
