/**
 * SourbyCraft's GitHub-release auto-updater (F1-8).
 *
 * <p>{@link dev.iyanz.sourbycraft.update.SourbyUpdater} polls the configured GitHub repo for a
 * newer release on the same {@link dev.iyanz.sourbycraft.update.UpdateChannel} as the running
 * build ({@link dev.iyanz.sourbycraft.update.SemVer} compares versions), downloads + verifies it,
 * and hands off to {@link dev.iyanz.sourbycraft.update.UpdateApplier} to swap the jar and restart
 * when {@code apply_mode=auto}. {@link dev.iyanz.sourbycraft.update.UpdateNotifier} renders the
 * hex-coloured "update available" banner to console + operators, and
 * {@link dev.iyanz.sourbycraft.update.ViaAutoUpdate} keeps the auto-provisioned ViaVersion /
 * ViaBackwards jars current on the same cadence. Settings are plain static fields in
 * {@link dev.iyanz.sourbycraft.update.AutoUpdateSettings}, seeded into and read back from the
 * unified TOML.
 */
package dev.iyanz.sourbycraft.update;
