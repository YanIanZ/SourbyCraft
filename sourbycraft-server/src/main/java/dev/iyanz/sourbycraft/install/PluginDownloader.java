package dev.iyanz.sourbycraft.install;

import java.io.IOException;
import java.io.InputStream;
import java.net.URI;
import java.net.URLDecoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.time.Duration;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

public final class PluginDownloader {

    private static final HttpClient HTTP = HttpClient.newBuilder()
        .connectTimeout(Duration.ofSeconds(10))
        .followRedirects(HttpClient.Redirect.NORMAL)
        .build();

    private PluginDownloader() {}

    public static Path download(PluginEntry entry, Path pluginsDir) throws IOException {
        String url;
        if (entry.isGithub()) {
            url = resolveGithubAsset(entry);
        } else if (entry.isJenkins()) {
            url = resolveJenkinsArtifact(entry);
        } else {
            url = entry.url();
        }
        if (url == null) return null;
        return downloadToFile(url, pluginsDir, entry.name());
    }

    /**
     * Resolve a Jenkins job's latest artifact by querying {@code <baseUrl>/api/json}.
     * {@code entry.url()} is the job's lastSuccessfulBuild URL (no trailing slash, no /artifact),
     * e.g. {@code https://ci.lucko.me/job/spark/lastSuccessfulBuild}.
     * {@code entry.assetGlob()} matches against {@code artifacts[].fileName}.
     */
    private static String resolveJenkinsArtifact(PluginEntry entry) throws IOException {
        String base = entry.url();
        if (base == null) return null;
        URI api = URI.create(base.replaceAll("/+$", "") + "/api/json?tree=artifacts[fileName,relativePath]");
        HttpRequest req = HttpRequest.newBuilder(api)
            .timeout(Duration.ofSeconds(15))
            .header("Accept", "application/json")
            .GET().build();
        try {
            HttpResponse<String> resp = HTTP.send(req, HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
            if (resp.statusCode() != 200) return null;
            Pattern artPat = Pattern.compile(
                "\"fileName\"\\s*:\\s*\"([^\"]+)\"\\s*,\\s*\"relativePath\"\\s*:\\s*\"([^\"]+)\"");
            Pattern globRe = entry.assetGlob() == null ? null : globToRegex(entry.assetGlob());
            Matcher m = artPat.matcher(resp.body());
            while (m.find()) {
                String fileName = m.group(1);
                String relPath = m.group(2);
                if (globRe == null || globRe.matcher(fileName).matches()) {
                    return base.replaceAll("/+$", "") + "/artifact/" + relPath;
                }
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IOException("Interrupted resolving Jenkins artifact", e);
        }
        return null;
    }

    private static String resolveGithubAsset(PluginEntry entry) throws IOException {
        URI api = URI.create("https://api.github.com/repos/" + entry.repo() + "/releases/latest");
        HttpRequest req = HttpRequest.newBuilder(api)
            .timeout(Duration.ofSeconds(15))
            .header("Accept", "application/vnd.github+json")
            .GET().build();
        try {
            HttpResponse<String> resp = HTTP.send(req, HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
            if (resp.statusCode() != 200) return null;
            // Parse JSON via regex to avoid a JSON library dep
            Pattern assetPat = Pattern.compile(
                "\"name\"\\s*:\\s*\"([^\"]+)\".{0,500}?\"browser_download_url\"\\s*:\\s*\"([^\"]+)\"",
                Pattern.DOTALL);
            Pattern globRe = entry.assetGlob() == null ? null : globToRegex(entry.assetGlob());
            Matcher m = assetPat.matcher(resp.body());
            while (m.find()) {
                String aname = m.group(1);
                String aurl = m.group(2);
                if (globRe == null || globRe.matcher(aname).matches()) {
                    return aurl;
                }
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IOException("Interrupted resolving GitHub asset", e);
        }
        return null;
    }

    private static Pattern globToRegex(String glob) {
        StringBuilder sb = new StringBuilder("^");
        for (char c : glob.toCharArray()) {
            switch (c) {
                case '*' -> sb.append(".*");
                case '?' -> sb.append('.');
                case '.', '(', ')', '+', '|', '^', '$', '@', '%' -> sb.append('\\').append(c);
                default -> sb.append(c);
            }
        }
        sb.append('$');
        return Pattern.compile(sb.toString());
    }

    private static final long MAX_DOWNLOAD_BYTES = 100L * 1024 * 1024; // 100 MB hard cap

    private static Path downloadToFile(String url, Path pluginsDir, String pluginName) throws IOException {
        URI uri = URI.create(url);
        String scheme = uri.getScheme();
        if (scheme == null || !scheme.equalsIgnoreCase("https")) {
            throw new IOException("Refusing non-https plugin download: " + url);
        }
        String fileName = extractFileName(uri, pluginName);
        Path pluginsRoot = pluginsDir.toAbsolutePath().normalize();
        Path target = pluginsRoot.resolve(fileName).normalize();
        if (!target.startsWith(pluginsRoot) || target.equals(pluginsRoot)) {
            throw new IOException("Refusing plugin write outside plugins dir: " + target);
        }
        HttpRequest req = HttpRequest.newBuilder(uri).timeout(Duration.ofMinutes(2)).GET().build();
        try {
            HttpResponse<InputStream> resp = HTTP.send(req, HttpResponse.BodyHandlers.ofInputStream());
            if (resp.statusCode() != 200) return null;
            long contentLength = resp.headers().firstValueAsLong("Content-Length").orElse(-1L);
            if (contentLength > MAX_DOWNLOAD_BYTES) {
                throw new IOException("Plugin download exceeds " + MAX_DOWNLOAD_BYTES + " bytes: " + url);
            }
            Path tmp = Files.createTempFile(pluginsRoot, ".sourbycraft-dl-", ".jar.tmp");
            try (InputStream in = resp.body();
                 java.io.OutputStream out = Files.newOutputStream(tmp)) {
                byte[] buf = new byte[64 * 1024];
                long written = 0;
                int n;
                while ((n = in.read(buf)) > 0) {
                    written += n;
                    if (written > MAX_DOWNLOAD_BYTES) {
                        out.close();
                        Files.deleteIfExists(tmp);
                        throw new IOException("Plugin download exceeds " + MAX_DOWNLOAD_BYTES + " bytes: " + url);
                    }
                    out.write(buf, 0, n);
                }
            }
            Files.move(tmp, target, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
            return target;
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IOException("Interrupted downloading " + url, e);
        }
    }

    private static String extractFileName(URI uri, String pluginName) {
        String path = uri.getPath();
        int slash = path == null ? -1 : path.lastIndexOf('/');
        String name = slash >= 0 ? path.substring(slash + 1) : (path == null ? "" : path);
        name = URLDecoder.decode(name, StandardCharsets.UTF_8);
        // Strip any path separators that survived decode to block traversal via "%2F.."
        name = name.replace('/', '_').replace('\\', '_');
        if (name.isBlank() || name.contains("..") || !name.endsWith(".jar")) {
            name = pluginName + ".jar";
        }
        return name;
    }
}
