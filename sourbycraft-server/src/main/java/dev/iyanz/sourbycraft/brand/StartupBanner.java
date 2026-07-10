package dev.iyanz.sourbycraft.brand;

import dev.iyanz.sourbycraft.security.HardeningAdvisor;

/**
 * Orchestrates SourbyCraft's branded console startup output.
 *
 * <p>Bundles the three pieces the Paper tag {@code paper-26.2-pre-folia} ran from
 * an NMS patch in {@code MinecraftServer#runServer}:
 * <ol>
 *   <li>the branded {@link SourbyCraftBanner} box,</li>
 *   <li>the {@link GcAdvisor} JVM/GC warning banner (warn-only, empty when the JVM
 *       is already tuned), and</li>
 *   <li>the {@link HardeningAdvisor} paper-global.yml scan (warn-only).</li>
 * </ol>
 *
 * <p>On the Folia base this is invoked once from the authored post-config hook
 * {@link me.earthme.luminol.commands.CommandRegister#register()} rather than a
 * {@code net/minecraft} patch, so the 82 minecraft / 17 paper patch series stay
 * untouched. Banner + GC advisor go straight to {@code System.out} (JLine renders
 * the embedded ANSI truecolor); the hardening advisor logs through the normal
 * SourbyCraft logger.
 */
public final class StartupBanner {

    private static volatile boolean printed = false;

    private StartupBanner() {}

    public static synchronized void printOnce() {
        if (printed) return;
        printed = true;
        try {
            // 1. branded banner
            System.out.print(SourbyCraftBanner.render(BuildInfo.load()));
            // 2. GC + JVM-arg advisor (warn-only; empty string when acceptable)
            String gcWarn = GcAdvisor.renderWarningBanner(GcAdvisor.run());
            if (!gcWarn.isEmpty()) {
                System.out.print(gcWarn);
            }
            System.out.flush();
            // 3. paper-global.yml hardening advisor (warn-only, logs each finding)
            HardeningAdvisor.run();
        } catch (Throwable t) {
            // Branding must never take the server down.
            dev.iyanz.sourbycraft.util.SourbyLogger.warn(
                "SourbyCraft startup banner failed: " + t.getMessage());
        }
    }
}
