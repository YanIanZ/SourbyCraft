package dev.iyanz.sourbycraft.security;

import dev.iyanz.sourbycraft.util.SourbyLogger;

import java.util.ArrayList;
import java.util.List;

/**
 * Boot-time advisor that scans Paper's GlobalConfiguration for known
 * exploit-friendly settings and logs an actionable warning per finding.
 *
 * <p>Read-only: operators keep full control of their paper-global.yml.
 * The advisor exists so a misconfigured deployment surfaces in the
 * boot log instead of waiting for an in-game incident.
 *
 * <p>Ported from the Paper tag {@code paper-26.2-pre-folia}. On the Folia base it
 * runs from the authored-source post-config hook
 * ({@link me.earthme.luminol.commands.CommandRegister#register()}) instead of an
 * NMS patch — by that point Paper's {@code GlobalConfiguration} is loaded.
 *
 * <p>Findings:
 *
 * <pre>
 *  unsupported-settings.allow-headless-pistons
 *  unsupported-settings.allow-permanent-block-break-exploits
 *  unsupported-settings.allow-piston-duplication
 *  unsupported-settings.allow-unsafe-end-portal-teleportation
 *  unsupported-settings.skip-tripwire-hook-placement-validation
 *  item-validation.book-size.page-max (warn when &gt; 4096)
 * </pre>
 */
public final class HardeningAdvisor {

    private HardeningAdvisor() {}

    public static void run() {
        try {
            io.papermc.paper.configuration.GlobalConfiguration cfg =
                io.papermc.paper.configuration.GlobalConfiguration.get();
            List<String> warnings = new ArrayList<>();
            io.papermc.paper.configuration.GlobalConfiguration.UnsupportedSettings unsup = cfg.unsupportedSettings;
            if (unsup.allowHeadlessPistons) {
                warnings.add("allow-headless-pistons=true (headless-piston abuse vector unlocked)");
            }
            if (unsup.allowPermanentBlockBreakExploits) {
                warnings.add("allow-permanent-block-break-exploits=true (bedrock / end-portal-frame break unlocked)");
            }
            if (unsup.allowPistonDuplication) {
                warnings.add("allow-piston-duplication=true (TNT / sand / rail / carpet dupe unlocked)");
            }
            if (unsup.allowUnsafeEndPortalTeleportation) {
                warnings.add("allow-unsafe-end-portal-teleportation=true (gravity-block dupe + load-spike unlocked)");
            }
            if (unsup.skipTripwireHookPlacementValidation) {
                warnings.add("skip-tripwire-hook-placement-validation=true (tripwire-hook dupe unlocked)");
            }
            int pageMax = cfg.itemValidation.bookSize.pageMax.or(0);
            if (pageMax > 4096) {
                warnings.add("item-validation.book-size.page-max=" + pageMax + " (>4096 enables book-bomb DOS; recommended <=2560)");
            }
            if (warnings.isEmpty()) return;
            SourbyLogger.warn("HardeningAdvisor: paper-global.yml carries " + warnings.size()
                + " exploit-friendly setting(s):");
            for (String w : warnings) {
                SourbyLogger.warn("  - " + w);
            }
            SourbyLogger.warn("Review config/paper-global.yml unsupported-settings if these are not intentional.");
        } catch (Throwable t) {
            // Advisory is non-fatal; never block boot if Paper internals shift.
            SourbyLogger.warn("HardeningAdvisor scan failed: " + t.getMessage());
        }
    }
}
