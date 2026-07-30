/**
 * Console-facing branding + diagnostics shown once at boot.
 *
 * <p>{@link dev.iyanz.sourbycraft.brand.StartupBanner} orchestrates the two pieces printed at
 * startup: the {@link dev.iyanz.sourbycraft.brand.SourbyCraftBanner} branded ANSI-truecolor box
 * (built from {@link dev.iyanz.sourbycraft.brand.BuildInfo}, the parsed
 * {@code META-INF/sourbycraft-build.properties}) and the {@link dev.iyanz.sourbycraft.brand.GcAdvisor}
 * JVM/GC flag warning. {@link dev.iyanz.sourbycraft.brand.PluginLoadDiagnostics} separately tails
 * the root logger to capture plugin-load failures for {@code /sys} to surface.
 */
package dev.iyanz.sourbycraft.brand;
