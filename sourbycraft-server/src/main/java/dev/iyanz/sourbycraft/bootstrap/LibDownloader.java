package dev.iyanz.sourbycraft.bootstrap;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.time.Duration;

final class LibDownloader {

    private static final HttpClient HTTP = HttpClient.newBuilder()
        .connectTimeout(Duration.ofSeconds(15))
        .followRedirects(HttpClient.Redirect.NORMAL)
        .build();

    private LibDownloader() {}

    /**
     * Downloads an entry into {@code librariesDir} at its declared paperclipPath.
     * Returns false on cache-hit (existing file matches SHA-256), true after a fresh download.
     * Throws IOException on any network, size, or hash failure.
     */
    static boolean ensure(BootstrapManifest.Entry entry, Path librariesDir) throws IOException {
        Path dest = librariesDir.resolve(entry.paperclipPath());
        if (Files.exists(dest) && Sha256Verifier.matches(dest, entry.sha256())) {
            return false;
        }
        Files.createDirectories(dest.getParent());
        Path tmp = dest.resolveSibling(dest.getFileName() + ".tmp");
        Files.deleteIfExists(tmp);

        HttpRequest req = HttpRequest.newBuilder(URI.create(entry.downloadUrl()))
            .timeout(Duration.ofMinutes(5))
            .GET().build();
        HttpResponse<Path> resp;
        try {
            resp = HTTP.send(req, HttpResponse.BodyHandlers.ofFile(tmp));
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IOException("interrupted during download of " + entry.downloadUrl(), e);
        }
        if (resp.statusCode() != 200) {
            Files.deleteIfExists(tmp);
            throw new IOException("HTTP " + resp.statusCode() + " for " + entry.downloadUrl());
        }
        if (Files.size(tmp) != entry.sizeBytes()) {
            Files.deleteIfExists(tmp);
            throw new IOException("size mismatch for " + entry.paperclipPath()
                + ": got " + Files.size(tmp) + ", expected " + entry.sizeBytes());
        }
        String actual = Sha256Verifier.ofFile(tmp);
        if (!entry.sha256().equalsIgnoreCase(actual)) {
            Files.deleteIfExists(tmp);
            throw new IOException("SHA-256 mismatch for " + entry.paperclipPath()
                + ": got " + actual + ", expected " + entry.sha256());
        }
        Files.move(tmp, dest, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
        return true;
    }
}
