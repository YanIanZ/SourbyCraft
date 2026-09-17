package dev.iyanz.sourbycraft.brand;

import java.io.FileDescriptor;
import java.io.FileOutputStream;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;

/**
 * Orchestrates SourbyCraft's branded console startup output.
 *
 * <p>Bundles the two pieces the archived Paper tag {@code paper-26.2-pre-folia} ran from an NMS
 * patch in {@code MinecraftServer#runServer}:
 * <ol>
 *   <li>the branded {@link SourbyCraftBanner} box, and</li>
 *   <li>the {@link GcAdvisor} JVM/GC warning banner (warn-only, empty when the JVM
 *       is already tuned).</li>
 * </ol>
 *
 * <p>The third archived piece — a {@code HardeningAdvisor} paper-global.yml exploit-setting scan —
 * lived under the {@code security} package, which is DEFERRED on this Canvas re-platform benchmark
 * build (feat/canvas-engine, PR #12) along with proxy-forwarding.
 *
 * <p>On this base this is invoked once from {@link dev.iyanz.sourbycraft.core.SourbyCraftBootstrap},
 * itself called from a hand-authored {@code minecraft-patch} to {@code DedicatedServer#initServer}.
 * Banner + GC advisor go straight to {@code System.out} (JLine renders the embedded ANSI truecolor).
 */
public final class StartupBanner {

    private static volatile boolean printed = false;

    /**
     * Dedicated UTF-8 stream over the real stdout file descriptor.
     *
     * <p>The banner and GC box embed Unicode box-drawing chars (U+2550 {@code ═}, etc.).
     * {@link System#out} encodes with {@code stdout.encoding}, which on a container / game
     * panel started under the C / POSIX locale resolves to US-ASCII (ANSI_X3.4-1968) — every
     * multi-byte box char then prints as {@code ?} / {@code �}. Writing the box through this
     * fixed UTF-8 {@link PrintStream} makes the output encoding-independent, so the border stays
     * intact regardless of the launch locale or {@code -Dstdout.encoding}. Falls back to
     * {@link System#out} if the console fd cannot be wrapped (headless / redirected edge cases).
     */
    private static final PrintStream UTF8_OUT = utf8ConsoleStream();

    private static PrintStream utf8ConsoleStream() {
        try {
            return new PrintStream(new FileOutputStream(FileDescriptor.out), true, StandardCharsets.UTF_8);
        } catch (Throwable t) {
            return System.out;
        }
    }

    private StartupBanner() {}

    /**
     * Prints the branded banner + GC advisor warning (if any) to the UTF-8 console stream. Only
     * the first call in the process prints anything; every later call is a no-op. Never throws —
     * a failure is logged as a warning instead of aborting boot.
     */
    public static synchronized void printOnce() {
        if (printed) return;
        printed = true;
        try {
            // Ensure ordering: flush anything already buffered on System.out before we write
            // the branded box to the raw fd, so lines do not interleave.
            System.out.flush();
            // 1. branded banner
            UTF8_OUT.print(SourbyCraftBanner.render(BuildInfo.load()));
            UTF8_OUT.flush();
            // 2. GC + JVM-arg advisor. Logged rather than written to the raw stream the banner
            //    uses: that stream reaches the console and never reaches logs/latest.log, and the
            //    operator who needs this most is the one reading logs after a lag incident to
            //    find out why the server was swapping.
            final GcAdvisor.Result advice = GcAdvisor.run();
            if (!advice.acceptable()) {
                dev.iyanz.sourbycraft.util.SourbyLogger.warn("JVM flag advisor:");
                for (final String warning : advice.warnings()) {
                    dev.iyanz.sourbycraft.util.SourbyLogger.warn("  - " + warning);
                }
                dev.iyanz.sourbycraft.util.SourbyLogger.warn(
                    "  Recommended (Java 25): -Xms2G -Xmx<75-85% of allocation> -XX:+UseZGC "
                    + "-XX:ZUncommitDelay=60 --add-modules=jdk.incubator.vector");
            }
        } catch (Throwable t) {
            // Branding must never take the server down.
            dev.iyanz.sourbycraft.util.SourbyLogger.warn(
                "SourbyCraft startup banner failed: " + t.getMessage());
        }
    }
}
