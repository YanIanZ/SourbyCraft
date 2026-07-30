/**
 * Pre-server-classes bootstrap: everything that runs before (or in order to reach) the real
 * Paper/Canvas server classes.
 *
 * <p>{@link dev.iyanz.sourbycraft.bootstrap.SourbyBootstrap} is the slim jar's {@code main}: it
 * auto-accepts the EULA, finishes a fallback-staged auto-update swap, runs the Auto-CDS layer
 * ({@link dev.iyanz.sourbycraft.bootstrap.CdsEnvironment} classifies the deployment so it never
 * forks a double-committing child JVM inside a capped container/panel), downloads any manifest
 * libraries via {@link dev.iyanz.sourbycraft.bootstrap.LibDownloader} +
 * {@link dev.iyanz.sourbycraft.bootstrap.Sha256Verifier}
 * ({@link dev.iyanz.sourbycraft.bootstrap.BootstrapManifest} describes what to fetch), provisions
 * the built-in ViaVersion/ViaBackwards jars via
 * {@link dev.iyanz.sourbycraft.bootstrap.PluginProvisioner}, and finally delegates to the
 * paperclip. {@link dev.iyanz.sourbycraft.bootstrap.MinecraftInternalPlugin} is the synthetic
 * {@code Plugin} handle SourbyCraft's own listeners/tasks register against, since server-internal
 * code has no real plugin instance.
 */
package dev.iyanz.sourbycraft.bootstrap;
