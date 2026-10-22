package dev.iyanz.sourbycraft.startup;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Path;
import java.util.Enumeration;
import java.util.Set;
import java.util.TreeSet;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;

/**
 * The class index and compatibility analysis of one plugin jar, derived from bytecode alone.
 *
 * <p>These are observations about what the jar references, not about what it does at runtime: a
 * reference to {@code BukkitScheduler} means the code can call it, not that it will. The analysis
 * feeds diagnostics and the Aurora Bridge's telemetry; it never decides whether a plugin loads.</p>
 *
 * @param classCount classes in the jar
 * @param packageCount distinct packages those classes live in
 * @param unreadableClasses class entries that were not well-formed class files
 * @param bukkitScheduler references the legacy Bukkit scheduler ({@code BukkitScheduler},
 *                        {@code BukkitRunnable}); under region threading this needs the bridge
 * @param regionSchedulers references the Folia region/entity/global/async schedulers
 * @param serverInternals references {@code net.minecraft} or {@code org.bukkit.craftbukkit}
 *                        internals, which no bridge can make region-safe
 */
public record CompatibilityScan(int classCount, int packageCount, int unreadableClasses, boolean bukkitScheduler,
                                boolean regionSchedulers, boolean serverInternals) {

    /** Class entries larger than this are skipped as unreadable rather than buffered. */
    static final long MAX_CLASS_BYTES = 8L * 1024 * 1024;
    /** Jars with more class entries than this are scanned up to the limit. */
    static final int MAX_CLASSES = 50_000;

    public static final CompatibilityScan EMPTY = new CompatibilityScan(0, 0, 0, false, false, false);

    public static CompatibilityScan of(final Path jar) throws IOException {
        int classes = 0;
        int unreadable = 0;
        boolean scheduler = false;
        boolean region = false;
        boolean internals = false;
        final Set<String> packages = new TreeSet<>();
        try (ZipFile zip = new ZipFile(jar.toFile())) {
            final Enumeration<? extends ZipEntry> entries = zip.entries();
            while (entries.hasMoreElements() && classes < MAX_CLASSES) {
                final ZipEntry entry = entries.nextElement();
                final String name = entry.getName();
                if (entry.isDirectory() || !name.endsWith(".class") || name.endsWith("module-info.class")) {
                    continue;
                }
                classes++;
                final int slash = name.lastIndexOf('/');
                packages.add(slash < 0 ? "" : name.substring(0, slash));
                if (entry.getSize() > MAX_CLASS_BYTES) {
                    unreadable++;
                    continue;
                }
                final Set<String> refs;
                try (InputStream in = zip.getInputStream(entry)) {
                    refs = BytecodeScanner.referencedClasses(in);
                } catch (final IOException malformed) {
                    unreadable++;
                    continue;
                }
                for (final String ref : refs) {
                    if (ref.equals("org/bukkit/scheduler/BukkitScheduler") || ref.equals("org/bukkit/scheduler/BukkitRunnable")) {
                        scheduler = true;
                    } else if (ref.startsWith("io/papermc/paper/threadedregions/scheduler/")) {
                        region = true;
                    } else if (ref.startsWith("net/minecraft/") || ref.startsWith("org/bukkit/craftbukkit/")) {
                        internals = true;
                    }
                }
            }
        }
        return new CompatibilityScan(classes, packages.size(), unreadable, scheduler, region, internals);
    }

    /** One word for logs and {@code /plugins}. */
    public String verdict(final boolean declaresRegionSupport) {
        if (declaresRegionSupport) return "declared";
        if (this.serverInternals) return "internals";
        if (this.bukkitScheduler) return "legacy-scheduler";
        return "undeclared";
    }

    String encode() {
        return this.classCount + "," + this.packageCount + "," + this.unreadableClasses + ","
            + this.bukkitScheduler + "," + this.regionSchedulers + "," + this.serverInternals;
    }

    static CompatibilityScan decode(final String text) {
        final String[] f = text.split(",", -1);
        if (f.length != 6) return null;
        try {
            return new CompatibilityScan(Integer.parseInt(f[0]), Integer.parseInt(f[1]), Integer.parseInt(f[2]),
                Boolean.parseBoolean(f[3]), Boolean.parseBoolean(f[4]), Boolean.parseBoolean(f[5]));
        } catch (final NumberFormatException malformed) {
            return null;
        }
    }
}
