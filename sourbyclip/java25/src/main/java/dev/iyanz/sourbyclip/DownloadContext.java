package dev.iyanz.sourbyclip;

import java.io.IOException;
import java.net.MalformedURLException;
import java.net.URI;
import java.net.URL;
import java.nio.channels.Channels;
import java.nio.channels.FileChannel;
import java.nio.channels.ReadableByteChannel;
import java.nio.file.Files;
import java.nio.file.Path;

import static java.nio.file.StandardOpenOption.*;

public record DownloadContext(byte[] hash, URL url, String fileName) {

    private static final long TRANSFER_CHUNK_BYTES = 8L << 20;

    public Path getOutputFile(final Path outputDir) {
        final Path cacheDir = outputDir.resolve("cache");
        return cacheDir.resolve(this.fileName);
    }

    public static DownloadContext parseLine(final String line) {
        if (line == null || line.isBlank()) {
            return null;
        }

        final String[] parts = line.split("\t");
        if (parts.length != 3) {
            throw new IllegalStateException("Invalid download-context line: " + line);
        }

        try {
            return new DownloadContext(Util.fromHex(parts[0]), URI.create(parts[1]).toURL(), parts[2]);
        } catch (final MalformedURLException e) {
            throw new IllegalStateException("Unable to parse URL in download-context", e);
        }
    }

    public void download(final Path outputDir) throws IOException {
        final Path outputFile = this.getOutputFile(outputDir);
        if (Files.exists(outputFile) && Util.isFileValid(outputFile, this.hash)) {
            return;
        }

        if (!Files.isDirectory(outputFile.getParent())) {
            Files.createDirectories(outputFile.getParent());
        }
        Files.deleteIfExists(outputFile);

        Sourbyclip.logger.info("Downloading {}", this.fileName);

        try (
                final ReadableByteChannel source = Channels.newChannel(this.url.openStream());
                final FileChannel fileChannel = FileChannel.open(outputFile, CREATE, WRITE, TRUNCATE_EXISTING)
        ) {
            // transferFrom moves *at most* count bytes and returns how many it actually moved;
            // it is not required to drain the source in one call, and a single unchecked call
            // silently writes a truncated file. Loop until the source reports end of stream.
            // Channels.newChannel gives a blocking channel, so a zero-byte transfer means EOF.
            long position = 0L;
            long transferred;
            while ((transferred = fileChannel.transferFrom(source, position, TRANSFER_CHUNK_BYTES)) > 0L) {
                position += transferred;
            }
        } catch (final IOException e) {
            Sourbyclip.logger.error(e, "Failed to download {}", this.fileName);
            e.printStackTrace();
            System.exit(1);
        }

        if (!Util.isFileValid(outputFile, this.hash)) {
            throw new IllegalStateException("Hash check failed for downloaded file " + this.fileName);
        }
    }
}
