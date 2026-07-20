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

    public static synchronized void printOnce() {
        if (printed) return;
        printed = true;
        try {
            // Ensure ordering: flush anything already buffered on System.out before we write
            // the branded box to the raw fd, so lines do not interleave.
            System.out.flush();
            // 1. branded banner
            UTF8_OUT.print(SourbyCraftBanner.render(BuildInfo.load()));
            // 2. GC + JVM-arg advisor (warn-only; empty string when acceptable)
            String gcWarn = GcAdvisor.renderWarningBanner(GcAdvisor.run());
            if (!gcWarn.isEmpty()) {
                UTF8_OUT.print(gcWarn);
            }
            UTF8_OUT.flush();
        } catch (Throwable t) {
            // Branding must never take the server down.
            dev.iyanz.sourbycraft.util.SourbyLogger.warn(
                "SourbyCraft startup banner failed: " + t.getMessage());
        }
    }
}
