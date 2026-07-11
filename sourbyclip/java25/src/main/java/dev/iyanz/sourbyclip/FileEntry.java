package dev.iyanz.sourbyclip;

import dev.iyanz.sourbyclip.downloader.Downloader;
import org.jetbrains.annotations.NotNull;

import java.io.BufferedReader;
import java.io.IOException;
import java.net.MalformedURLException;
import java.net.URL;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.Map;
import java.util.concurrent.CompletableFuture;

public record FileEntry(byte[] hash, String id, String path) {

    public static FileEntry[] parse(final BufferedReader reader) throws IOException {
        var result = new FileEntry[8];

        int index = 0;
        String line;
        while ((line = reader.readLine()) != null) {
            final FileEntry data = parseLine(line);
            if (index == result.length) {
                result = Arrays.copyOf(result, index * 2);
            }
            result[index++] = data;
        }

        if (index != result.length) {
            return Arrays.copyOf(result, index);
        } else {
            return result;
        }
    }

    private static FileEntry parseLine(final String line) {
        final var parts = line.split("\t");
        if (parts.length != 3) {
            throw new IllegalStateException("Malformed library entry: " + line);
        } else {
            return new FileEntry(Util.fromHex(parts[0]), parts[1], parts[2]);
        }
    }

    public CompletableFuture<Void> downloadFromMvnRepo(
            final Map<String, URL> urls,
            final PatchEntry @NotNull [] patches,
            final String targetName,
            final Path originalRootDir,
            final String baseDir,
            final Path outputDir
    ) throws IOException {
        for (final PatchEntry patch : patches) {
            if (patch.location().equals(targetName) && patch.outputPath().equals(this.path)) {
                // This file will be created from a patch
                return CompletableFuture.completedFuture(null);
            }
        }

        final Path outputFile = outputDir.resolve(this.path);
        if (Files.exists(outputFile) && Util.isFileValid(outputFile, this.hash)) {
            urls.put(this.path, outputFile.toUri().toURL());
            return CompletableFuture.completedFuture(null);
        }

        Sourbyclip.logger.info("Downloading missing file " + this.id + " to " + outputFile + " .");

        final @NotNull CompletableFuture<Path> task = new Downloader(this, outputDir, outputFile, baseDir, originalRootDir, true).downloadOrLoad(Sourbyclip.DOWNLOAD_EXECUTOR);

        return task.thenAccept(ret -> {
            synchronized (urls) {
                try {
                    urls.put(this.path, ret.toUri().toURL());
                } catch (MalformedURLException e) {
                    throw new RuntimeException(e);
                }
            }
        });
    }
}
