package dev.iyanz.sourbycraft.lang;

import java.util.function.Consumer;
import net.kyori.adventure.text.Component;

/**
 * Applies {@code messages.motd} to the running server's server-list MOTD once at startup.
 *
 * <p>The value is handed to {@code Bukkit.motd(Component)}, which sets the in-memory MOTD only. It
 * is not written back to {@code server.properties}, so that file keeps the operator's own MOTD and
 * stays the fallback. One variant is picked per boot (RESTART_REQUIRED); a reload does not change
 * the MOTD of a running server.</p>
 *
 * <p>An explicit empty list ({@code motd = []}) leaves the {@code server.properties} MOTD alone.
 * An absent key applies the built-in variants, which is also what the seeded default holds.</p>
 */
public final class SourbyMotd {

    private SourbyMotd() {}

    /**
     * Hands one {@code messages.motd} variant to {@code sink}.
     *
     * @return true when a variant was applied, false when the operator disabled the override
     */
    public static boolean apply(final Consumer<Component> sink) {
        if (SourbyMessages.isDisabled(SourbyMessages.MOTD)) {
            return false;
        }
        final Component motd = SourbyMessages.get(SourbyMessages.MOTD);
        if (Component.empty().equals(motd)) {
            return false;
        }
        sink.accept(motd);
        return true;
    }
}
