package dev.iyanz.sourbycraft.startup;

import java.io.IOException;
import java.io.Reader;
import java.io.Writer;
import java.nio.charset.StandardCharsets;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;
import java.util.Properties;

/**
 * One boot's startup timing: total time to ready and per-phase durations, kept so the next boot
 * can say how it compares.
 *
 * <p>The comparison is only reported between boots of the same start class (cold, warm, mixed).
 * A warm boot faster than the previous cold one is expected, not an improvement, and is not
 * presented as one.</p>
 *
 * @param startClass the startup index's start class for this boot
 * @param totalMillis JVM start to server ready
 * @param phaseMillis phases in the order they ran
 */
public record StartupProfile(String startClass, long totalMillis, Map<String, Long> phaseMillis) {

    static final int FORMAT = 1;

    public StartupProfile {
        Objects.requireNonNull(startClass, "startClass");
        phaseMillis = java.util.Collections.unmodifiableMap(new LinkedHashMap<>(phaseMillis));
    }

    /** The profile stored at {@code file}, or {@code null} when absent or unreadable. */
    public static StartupProfile load(final Path file) {
        if (!Files.isRegularFile(file)) return null;
        final Properties p = new Properties();
        try (Reader reader = Files.newBufferedReader(file, StandardCharsets.UTF_8)) {
            p.load(reader);
            if (!Integer.toString(FORMAT).equals(p.getProperty("format"))) return null;
            final String startClass = p.getProperty("start-class");
            final long total = Long.parseLong(p.getProperty("total-ms"));
            final int phases = Integer.parseInt(p.getProperty("phases", "0"));
            final Map<String, Long> phaseMillis = new LinkedHashMap<>();
            for (int i = 0; i < phases; i++) {
                phaseMillis.put(p.getProperty("phase." + i + ".name"), Long.parseLong(p.getProperty("phase." + i + ".ms")));
            }
            return startClass == null || phaseMillis.containsKey(null) ? null
                : new StartupProfile(startClass, total, phaseMillis);
        } catch (final IOException | RuntimeException unreadable) {
            return null;
        }
    }

    /** Writes atomically; a failure leaves the previous profile. */
    public void save(final Path file) throws IOException {
        final Properties p = new Properties();
        p.setProperty("format", Integer.toString(FORMAT));
        p.setProperty("start-class", this.startClass);
        p.setProperty("total-ms", Long.toString(this.totalMillis));
        p.setProperty("phases", Integer.toString(this.phaseMillis.size()));
        int i = 0;
        for (final Map.Entry<String, Long> phase : this.phaseMillis.entrySet()) {
            p.setProperty("phase." + i + ".name", phase.getKey());
            p.setProperty("phase." + i + ".ms", Long.toString(phase.getValue()));
            i++;
        }
        final Path parent = file.toAbsolutePath().getParent();
        Files.createDirectories(parent);
        final Path temp = Files.createTempFile(parent, file.getFileName().toString(), ".tmp");
        try {
            try (Writer writer = Files.newBufferedWriter(temp, StandardCharsets.UTF_8)) {
                p.store(writer, "Aurora startup profile");
            }
            try {
                Files.move(temp, file, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
            } catch (final AtomicMoveNotSupportedException notAtomic) {
                Files.move(temp, file, StandardCopyOption.REPLACE_EXISTING);
            }
        } finally {
            Files.deleteIfExists(temp);
        }
    }

    /**
     * A one-line comparison with the previous boot, or a statement that there is nothing
     * comparable.
     */
    public String compareTo(final StartupProfile previous) {
        if (previous == null) {
            return "no previous profile";
        }
        if (!previous.startClass.equals(this.startClass)) {
            return "previous boot was " + previous.startClass + " (" + previous.totalMillis
                + " ms); not comparable with this " + this.startClass + " boot";
        }
        final long delta = this.totalMillis - previous.totalMillis;
        return "previous " + previous.startClass + " boot " + previous.totalMillis + " ms ("
            + (delta >= 0 ? "+" : "") + delta + " ms); single-boot difference, not a benchmark";
    }
}
