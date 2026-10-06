/**
 * Bootstrap services and server-owned plugin provisioning.
 *
 * <p>The current slim jar enters through SourbyClip, which loads verified libraries and the
 * server. The SourbyPatcher feature patch in Minecraft Main then calls
 * {@link dev.iyanz.sourbycraft.bootstrap.PluginProvisioner} before Paper scans plugins.
 * ViaVersion/ViaBackwards and ProtocolLib use separate provisioning settings. The legacy
 * {@link dev.iyanz.sourbycraft.bootstrap.SourbyBootstrap} remains a separate entrypoint;
 * its CDS/EULA/manifest-download behavior must not be inferred to run in the current launcher.
 * {@link dev.iyanz.sourbycraft.bootstrap.MinecraftInternalPlugin} is the synthetic
 * {@code Plugin} handle SourbyCraft's own listeners/tasks register against, since server-internal
 * code has no real plugin instance.
 */
package dev.iyanz.sourbycraft.bootstrap;
