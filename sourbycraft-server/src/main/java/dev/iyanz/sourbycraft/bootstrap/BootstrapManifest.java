package dev.iyanz.sourbycraft.bootstrap;

import java.util.List;

/**
 * Immutable manifest of libraries the SourbyBootstrap must materialize before
 * delegating to Paperclip. Baked into the slim jar at build time as
 * META-INF/sourby-bootstrap-manifest.json by the createSlimPaperclipJar gradle task.
 */
public record BootstrapManifest(List<Entry> entries) {

    /**
     * One downloadable library or resource bundle.
     *
     * @param paperclipPath  destination relative to the paperclip libraries/ dir
     *                       (e.g. "org/xerial/sqlite-jdbc/3.49.1.0/sqlite-jdbc-3.49.1.0.jar")
     * @param downloadUrl    direct URL to fetch the file from
     * @param sha256         hex-encoded SHA-256 of the file (64 chars, lowercase)
     * @param sizeBytes      expected length in bytes
     */
    public record Entry(String paperclipPath, String downloadUrl, String sha256, long sizeBytes) {}
}
