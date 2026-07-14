package dev.iyanz.sourbyclip.update;

import dev.iyanz.sourbyclip.Sourbyclip;

import java.io.BufferedReader;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.net.URISyntaxException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.jar.JarEntry;
import java.util.jar.JarFile;

public final class AutoUpdate {
    private static final Path AUTO_UPDATE_DIR = Path.of("auto_update");
    private static final Path CORE_PATH_FILE = AUTO_UPDATE_DIR.resolve("core.path");
    private static final String VERSION_RESOURCE = "/META-INF/sourbyclip-version";

    private static volatile Path autoUpdateCorePath;
    private static volatile boolean useAutoUpdateJar;

    private AutoUpdate() {
    }

    public static void init() {
        autoUpdateCorePath = null;
        useAutoUpdateJar = false;

        if (!Files.isDirectory(AUTO_UPDATE_DIR) || !Files.isRegularFile(CORE_PATH_FILE)) {
            return;
        }

        try (BufferedReader reader = Files.newBufferedReader(CORE_PATH_FILE, StandardCharsets.UTF_8)) {
            final String configuredPath = reader.readLine();
            if (configuredPath == null || configuredPath.isBlank()) {
                return;
            }

            final Path jarPath = Path.of(configuredPath.trim()).toAbsolutePath().normalize();
            if (!Files.isRegularFile(jarPath)) {
                Sourbyclip.logger.error("The specified auto-update jar {} does not exist.", jarPath);
                System.exit(1);
            }

            if (isCurrentLauncherJar(jarPath)) {
                return;
            }

            autoUpdateCorePath = jarPath;
            useAutoUpdateJar = true;

            if (!detectionSourbyclipVersion()) {
                Sourbyclip.logger.error("Sourbyclip version detection in auto-update jar {} failed.", jarPath);
                System.exit(1);
            }

            Sourbyclip.logger.info("Using auto-update target jar {}", jarPath);
        } catch (IOException e) {
            Sourbyclip.logger.error("Failed to read core path file.", e);
            System.exit(1);
        }
    }

    public static InputStream getResourceAsStreamFromTargetJar(String name) {
        final String resourcePath = normalizeLookupPath(name);

        if (!useAutoUpdateJar || autoUpdateCorePath == null) {
            return AutoUpdate.class.getResourceAsStream("/" + resourcePath);
        }

        try (JarFile targetJar = new JarFile(autoUpdateCorePath.toFile())) {
            final JarEntry entry = targetJar.getJarEntry(resourcePath);
            if (entry == null) {
                return null;
            }

            try (InputStream stream = targetJar.getInputStream(entry)) {
                return new ByteArrayInputStream(stream.readAllBytes());
            }
        } catch (IOException e) {
            Sourbyclip.logger.error(e, "Failed to get resource {} from target jar {}.", resourcePath, autoUpdateCorePath);
            return null;
        }
    }

    private static boolean detectionSourbyclipVersion() {
        if (Boolean.getBoolean("sourbyclip.skip-version-check")
                || Boolean.getBoolean("sourbyclip.skip-sourbyclip-version-check")) {
            return true;
        }

        final byte[] localBytes;
        try (InputStream localStream = AutoUpdate.class.getResourceAsStream(VERSION_RESOURCE)) {
            if (localStream == null) {
                return false;
            }
            localBytes = localStream.readAllBytes();
        } catch (IOException e) {
            throw new RuntimeException(e);
        }

        try (InputStream externalStream = getResourceAsStreamFromTargetJar(VERSION_RESOURCE)) {
            if (externalStream == null) {
                return false;
            }
            return Arrays.equals(localBytes, externalStream.readAllBytes());
        } catch (IOException e) {
            throw new RuntimeException(e);
        }
    }

    private static boolean isCurrentLauncherJar(Path targetJar) {
        final Path currentJar = getCurrentLauncherJarPath();
        if (currentJar == null) {
            return false;
        }

        try {
            return Files.isSameFile(currentJar, targetJar);
        } catch (IOException e) {
            return currentJar.equals(targetJar);
        }
    }

    private static Path getCurrentLauncherJarPath() {
        try {
            if (Sourbyclip.class.getProtectionDomain().getCodeSource() == null) {
                return null;
            }

            return Path.of(Sourbyclip.class.getProtectionDomain().getCodeSource().getLocation().toURI())
                    .toAbsolutePath()
                    .normalize();
        } catch (URISyntaxException | IllegalArgumentException e) {
            return null;
        }
    }

    private static String normalizeLookupPath(String name) {
        if (name.startsWith("/")) {
            return name.substring(1);
        }
        return name;
    }
}
