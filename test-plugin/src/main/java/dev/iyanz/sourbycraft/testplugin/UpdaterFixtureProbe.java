package dev.iyanz.sourbycraft.testplugin;

import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.io.StringReader;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.util.HexFormat;
import java.util.Optional;
import java.util.Properties;
import java.util.logging.Logger;

/**
 * Test-server half of the auto-updater rollback fixture ({@code scripts/updater_rollback_fixture.py},
 * {@code docs/development/updater-rollback-fixture-2026-10-22.md}). Only runs with
 * {@code -Dsourby.testplugin.updater-fixture=true}; CI and every other boot never touch it.
 *
 * <p>The fixture builds three variants of the same slim jar and tells them apart with one extra
 * entry, {@value #MARKER}, in the launch jar (the first {@code java.class.path} element). The marker is
 * read through the system class loader, i.e. from the jar file this JVM opened at start: after the
 * rollback guard copies {@code .prev} over the launch jar in the middle of a boot, the path holds the
 * restored bytes but the running JVM still executes (and reads) the old inode, and the probe reports
 * what is RUNNING ({@code role}) next to what is on disk ({@code disk-sha256}).
 * {@code role=original|new-good|new-broken}, {@code boot=ok|fail} and, for {@code fail},
 * {@code exit-code}. This probe logs which jar booted, with its SHA-256, and for a {@code fail}
 * jar halts the JVM with that exit code from {@code onLoad}: after the SourbyCraft boot stage that
 * runs the rollback guard (so the guard's real code has recorded {@code BOOTING} or restored the
 * previous jar), and before worlds load and {@code ServerLoadEvent STARTUP}, i.e. before ready. A
 * jar without the entry is logged as {@code role=unmarked} and boots normally.
 */
final class UpdaterFixtureProbe {

    static final String PROPERTY = "sourby.testplugin.updater-fixture";
    static final String MARKER = "META-INF/sourbycraft-fixture.properties";
    static final int MAX_MARKER_BYTES = 4096;
    static final int DEFAULT_EXIT_CODE = 3;

    private UpdaterFixtureProbe() {}

    /** What the launch jar's marker asks for. */
    record Marker(String role, boolean failBeforeReady, int exitCode) {}

    static boolean enabled() {
        return Boolean.getBoolean(PROPERTY);
    }

    /** Parse the marker text; unknown or missing keys fall back to a normal boot. */
    static Marker parse(final String text) throws IOException {
        final Properties props = new Properties();
        props.load(new StringReader(text));
        final String role = props.getProperty("role", "unmarked").trim();
        final boolean fail = "fail".equalsIgnoreCase(props.getProperty("boot", "ok").trim());
        int exit = DEFAULT_EXIT_CODE;
        try {
            exit = Integer.parseInt(props.getProperty("exit-code", String.valueOf(DEFAULT_EXIT_CODE)).trim());
        } catch (final NumberFormatException keepDefault) {
            // a fail marker with a garbage code still fails, with the default code
        }
        if (fail && (exit < 1 || exit > 255)) exit = DEFAULT_EXIT_CODE;
        return new Marker(role.isEmpty() ? "unmarked" : role, fail, exit);
    }

    /** The marker read from {@code in} (at most 4 KiB), or empty when {@code in} is null (no marker entry). */
    static Optional<Marker> read(final InputStream in) throws IOException {
        if (in == null) return Optional.empty();
        try (in) {
            final byte[] bytes = in.readNBytes(MAX_MARKER_BYTES + 1);
            if (bytes.length > MAX_MARKER_BYTES) throw new IOException(MARKER + " is larger than " + MAX_MARKER_BYTES + " bytes");
            return Optional.of(parse(new String(bytes, java.nio.charset.StandardCharsets.UTF_8)));
        }
    }

    /** The marker of the jar this JVM is running (system class loader), or empty when the jar has none. */
    static Optional<Marker> readRunning() throws IOException {
        return read(ClassLoader.getSystemResourceAsStream(MARKER));
    }

    /** First {@code java.class.path} element, i.e. the {@code -jar} argument, or empty. */
    static Optional<Path> launchJar() {
        final String cp = System.getProperty("java.class.path", "");
        final String first = cp.split(File.pathSeparator, 2)[0];
        return first.isBlank() ? Optional.empty() : Optional.of(Path.of(first));
    }

    static String sha256(final Path file) throws Exception {
        final MessageDigest digest = MessageDigest.getInstance("SHA-256");
        try (InputStream in = Files.newInputStream(file)) {
            final byte[] buffer = new byte[64 * 1024];
            for (int n; (n = in.read(buffer)) > 0; ) digest.update(buffer, 0, n);
        }
        return HexFormat.of().formatHex(digest.digest());
    }

    /** Called first thing from {@code onLoad}. Never returns for a {@code boot=fail} jar. */
    static void run(final Logger log) {
        if (!enabled()) return;
        try {
            final Optional<Path> jar = launchJar();
            if (jar.isEmpty() || !Files.isRegularFile(jar.get())) {
                log.warning("SOURBY_UPDATER_FIXTURE no launch jar on java.class.path (" + System.getProperty("java.class.path") + ")");
                return;
            }
            final Marker marker = readRunning().orElse(new Marker("unmarked", false, DEFAULT_EXIT_CODE));
            log.info("SOURBY_UPDATER_FIXTURE jar=" + jar.get().getFileName() + " role=" + marker.role()
                + " disk-sha256=" + sha256(jar.get()) + " boot=" + (marker.failBeforeReady() ? "fail" : "ok"));
            if (marker.failBeforeReady()) {
                log.severe("SOURBY_UPDATER_FIXTURE_FAIL role=" + marker.role() + " exit=" + marker.exitCode()
                    + ": this jar is built to die before ready (before worlds load and ServerLoadEvent STARTUP).");
                System.out.flush();
                System.err.flush();
                Thread.sleep(300L); // let the console appender write the lines above
                Runtime.getRuntime().halt(marker.exitCode());
            }
        } catch (final Exception problem) {
            log.severe("SOURBY_UPDATER_FIXTURE could not read the launch jar: " + problem);
        }
    }
}
