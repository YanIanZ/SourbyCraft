/**
 * Console-facing branding + diagnostics shown once at boot (the Build 49 console design).
 *
 * <p>{@link dev.iyanz.sourbycraft.brand.LaunchLines} renders the two lines that open the console
 * (build identity from {@link dev.iyanz.sourbycraft.brand.BuildInfo}, the parsed
 * {@code META-INF/sourbycraft-build.properties}, and the runtime), logged by Paper's bootstrap.
 * {@link dev.iyanz.sourbycraft.brand.ConsoleSections} prints the section rules (Engine, Plugins,
 * Worlds, Plugins (post-world), Ready); {@link dev.iyanz.sourbycraft.brand.AuroraBoot} the engine
 * stage summary; {@link dev.iyanz.sourbycraft.brand.ReadySummary} the one-line summary before
 * Minecraft's {@code Done (}; {@link dev.iyanz.sourbycraft.brand.JvmAdvisor} the
 * {@link dev.iyanz.sourbycraft.brand.GcAdvisor} JVM/GC flag warnings. All of it is plain text on
 * the normal loggers: colour is the console layout's job ({@code log4j2.xml}), terminal only.
 * {@link dev.iyanz.sourbycraft.brand.PluginLoadDiagnostics} separately tails the root logger to
 * capture plugin-load failures for {@code /sys} to surface.
 */
package dev.iyanz.sourbycraft.brand;
