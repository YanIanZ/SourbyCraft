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

    private static Path downloadToFile(String url, Path pluginsDir, String pluginName) throws IOException {
        URI uri = URI.create(url);
        String fileName = extractFileName(uri, pluginName);
        Path target = pluginsDir.resolve(fileName);
        HttpRequest req = HttpRequest.newBuilder(uri).timeout(Duration.ofMinutes(2)).GET().build();
        try {
            HttpResponse<InputStream> resp = HTTP.send(req, HttpResponse.BodyHandlers.ofInputStream());
            if (resp.statusCode() != 200) return null;
            try (InputStream in = resp.body()) {
                Files.copy(in, target, StandardCopyOption.REPLACE_EXISTING);
            }
            return target;
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IOException("Interrupted downloading " + url, e);
        }
    }

    private static String extractFileName(URI uri, String pluginName) {
        String path = uri.getPath();
        int slash = path.lastIndexOf('/');
        String name = slash >= 0 ? path.substring(slash + 1) : path;
        name = URLDecoder.decode(name, StandardCharsets.UTF_8);
        if (name.isBlank() || !name.endsWith(".jar")) {
            name = pluginName + ".jar";
        }
        return name;
    }
}
