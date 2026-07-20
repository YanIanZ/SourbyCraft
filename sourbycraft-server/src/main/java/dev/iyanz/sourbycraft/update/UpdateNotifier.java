package dev.iyanz.sourbycraft.update;

import dev.iyanz.sourbycraft.SourbyCraftColors;
import dev.iyanz.sourbycraft.bootstrap.MinecraftInternalPlugin;
import dev.iyanz.sourbycraft.util.SourbyLogger;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.event.ClickEvent;
import net.kyori.adventure.text.format.TextColor;
import net.kyori.adventure.text.format.TextDecoration;
import org.bukkit.Bukkit;

/**
 * Hex-colored operator notification for the SourbyCraft auto-updater (F1-8).
 *
 * <p>Renders the "update available" banner in the SourbyCraft palette
 * ({@link SourbyCraftColors}) and delivers it to every recipient who should see it:
 * <ul>
 *   <li>the server console (always), as a multi-line Adventure banner;</li>
 *   <li>every online operator ({@code isOp()} or the {@code sourbycraft.update.notify}
 *       permission), so an admin who logs in during the run also gets pinged.</li>
 * </ul>
 *
 * <p>All output goes through Adventure {@link Component}s with explicit hex
 * {@link TextColor}s — the "op notification in hex" requirement. The release URL is
 * click-to-open. Delivery is defensive: a failure to reach players (e.g. called before
 * the server is fully up) never propagates out of the updater.
 */
public final class UpdateNotifier {

    /** Permission that, in addition to op, receives the in-game update banner. */
    public static final String NOTIFY_PERMISSION = "sourbycraft.update.notify";

    // The most recent "update available" banner, kept so a permission holder who joins AFTER the
    // check ran still gets pinged (see JoinListener). Cleared when the server reports up-to-date.
    private static volatile Component pendingBanner;
    private static volatile String pendingLatest;

    private UpdateNotifier() {}

    /** Clear the pending banner (e.g. the check found the server already up to date). */
    public static void clearPending() {
        pendingBanner = null;
        pendingLatest = null;
    }

    /** True while an update banner is outstanding for join-time notification. */
    public static boolean hasPending() { return pendingBanner != null; }

    /**
     * Join listener: a permission holder (op or {@link #NOTIFY_PERMISSION}) who logs in after an
     * update was detected still gets the banner, shown a couple seconds post-join so it lands after
     * the join spam. Registered once from the updater start.
     */
    public static final class JoinListener implements org.bukkit.event.Listener {
        @org.bukkit.event.EventHandler
        public void onJoin(org.bukkit.event.player.PlayerJoinEvent e) {
            final Component banner = pendingBanner;
            if (banner == null) return;
            final org.bukkit.entity.Player p = e.getPlayer();
            if (!(p.isOp() || p.hasPermission(NOTIFY_PERMISSION))) return;
            // Hop to the player's region + small delay so the banner isn't buried by join messages.
            p.getScheduler().runDelayed(MinecraftInternalPlugin.INSTANCE,
                task -> { if (pendingBanner != null) p.sendMessage(pendingBanner); }, null, 40L);
        }
    }

    /**
     * Announce an available update. {@code current}/{@code latest} are the full version strings
     * (e.g. {@code 26.2-REL}), {@code channel} the shared channel, {@code releaseUrl} the GitHub
     * release page, and {@code notes} an optional one-line changelog/release-notes summary
     * (may be null/blank).
     */
    public static void announce(String current, String latest, UpdateChannel channel,
                                String releaseUrl, String notes) {
        Component banner = buildBanner(current, latest, channel, releaseUrl, notes);
        pendingBanner = banner; // for join-time notification of permission holders
        pendingLatest = latest;

        // Console — always. Also mirror a plain line to the SourbyCraft logger for log scrapers.
        try {
            Bukkit.getConsoleSender().sendMessage(banner);
        } catch (Throwable t) {
            SourbyLogger.warn("update notify: console send failed: " + t.getMessage());
        }
        SourbyLogger.info("update available: " + current + " -> " + latest
            + " (" + channel.suffix() + ")  " + (releaseUrl == null ? "" : releaseUrl));

        // Online operators / permission holders.
        try {
            for (var player : Bukkit.getOnlinePlayers()) {
                if (player.isOp() || player.hasPermission(NOTIFY_PERMISSION)) {
                    player.sendMessage(banner);
                }
            }
        } catch (Throwable t) {
            SourbyLogger.warn("update notify: player broadcast failed: " + t.getMessage());
        }
    }

    /** Build the hex-colored multi-line banner Component. Package-visible for reuse/testing. */
    static Component buildBanner(String current, String latest, UpdateChannel channel,
                                 String releaseUrl, String notes) {
        final TextColor frame  = SourbyCraftColors.PRIMARY; // #FFB347
        final TextColor label  = SourbyCraftColors.LABEL;   // #FFD700
        final TextColor value  = SourbyCraftColors.SUCCESS; // #77DD77
        final TextColor info   = SourbyCraftColors.INFO;    // #AEC6CF
        final TextColor accent  = SourbyCraftColors.ACCENT; // #CBA6F7

        Component nl = Component.newline();

        Component header = Component.text("  SourbyCraft ", frame, TextDecoration.BOLD)
            .append(Component.text("Auto-Update", accent, TextDecoration.BOLD));

        Component versionLine = Component.text("  update available: ", label)
            .append(Component.text(current, info))
            .append(Component.text("  →  ", frame))
            .append(Component.text(latest, value, TextDecoration.BOLD))
            .append(Component.text("  (" + channel.suffix() + ")", accent));

        Component banner = Component.empty()
            .append(header).append(nl)
            .append(versionLine);

        if (releaseUrl != null && !releaseUrl.isBlank()) {
            banner = banner.append(nl).append(
                Component.text("  release: ", label)
                    .append(Component.text(releaseUrl, info)
                        .clickEvent(ClickEvent.openUrl(releaseUrl))));
        }
        if (notes != null && !notes.isBlank()) {
            String trimmed = notes.length() > 160 ? notes.substring(0, 157) + "..." : notes;
            banner = banner.append(nl).append(
                Component.text("  notes: ", label).append(Component.text(trimmed, info)));
        }
        return banner;
    }
}
