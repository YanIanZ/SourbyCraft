package dev.iyanz.sourbyclip.downloader;

import org.leavesmc.leavesclip.logger.SimpleLogger;
import org.w3c.dom.Document;
import org.w3c.dom.Element;
import org.w3c.dom.NodeList;

import javax.xml.parsers.DocumentBuilder;
import javax.xml.parsers.DocumentBuilderFactory;
import java.io.*;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.*;
import java.util.stream.Collectors;

public class MavenDependencyResolver {
    private static final SimpleLogger logger = new SimpleLogger("MavenDependencyResolver");

    private static final int BUFFER_SIZE = 8192;
    private static final int TIMEOUT = 30000;

    private static final Map<String, String> PACKAGING_TO_EXTENSION = new HashMap<>();

    static {
        PACKAGING_TO_EXTENSION.put("jar", "jar");
        PACKAGING_TO_EXTENSION.put("war", "war");
        PACKAGING_TO_EXTENSION.put("ear", "ear");
        PACKAGING_TO_EXTENSION.put("rar", "rar");
        PACKAGING_TO_EXTENSION.put("pom", "pom");
        PACKAGING_TO_EXTENSION.put("maven-plugin", "jar");
        PACKAGING_TO_EXTENSION.put("bundle", "jar");
        PACKAGING_TO_EXTENSION.put("eclipse-plugin", "jar");
        PACKAGING_TO_EXTENSION.put("eclipse-feature", "jar");
        PACKAGING_TO_EXTENSION.put("ejb", "jar");
        PACKAGING_TO_EXTENSION.put("ejb-client", "jar");
        PACKAGING_TO_EXTENSION.put("test-jar", "jar");
        PACKAGING_TO_EXTENSION.put("java-source", "jar");
        PACKAGING_TO_EXTENSION.put("javadoc", "jar");
        PACKAGING_TO_EXTENSION.put("gradle-plugin", "jar");
        PACKAGING_TO_EXTENSION.put("zip", "zip");
        PACKAGING_TO_EXTENSION.put("tar.gz", "tar.gz");
        PACKAGING_TO_EXTENSION.put("tar.bz2", "tar.bz2");
    }

    private final List<MavenRepository> repositories;
    private final Path defaultLocalRepo;

    public MavenDependencyResolver(List<MavenRepository> repositories, Path localRepoPath) {
        this.repositories = new ArrayList<>(repositories);
        this.defaultLocalRepo = localRepoPath;
    }

    private static List<MavenRepository> getDefaultRepositories() {
        List<MavenRepository> repos = new ArrayList<>();
        repos.add(new MavenRepository("central", "https://repo1.maven.org/maven2/"));
        repos.add(new MavenRepository("sonatype", "https://oss.sonatype.org/content/repositories/snapshots/"));
        return repos;
    }

    public static class MavenRepository {
        final String id;
        final String url;
        final boolean snapshotsEnabled;
        final boolean releasesEnabled;

        public MavenRepository(String id, String url) {
            this(id, url, true, true);
        }

        public MavenRepository(String id, String url, boolean releasesEnabled, boolean snapshotsEnabled) {
            this.id = id;
            this.url = url.endsWith("/") ? url : url + "/";
            this.snapshotsEnabled = snapshotsEnabled;
            this.releasesEnabled = releasesEnabled;
        }

        public boolean supports(boolean isSnapshot) {
            return isSnapshot ? snapshotsEnabled : releasesEnabled;
        }

        @Override
        public String toString() {
            return id + " (" + url + ")";
        }
    }

    /**
     * Maven 坐标 - 修复了 SNAPSHOT 路径问题
     */
    public static class MavenCoordinate {
        final String groupId;
        final String artifactId;
        final String version;
        String packaging;
        String classifier;

        boolean isSnapshot;
        String snapshotVersion;      // 具体的时间戳版本，如 0.1-20240720.200737-2
        String snapshotTimestamp;
        String snapshotBuildNumber;

        public MavenCoordinate(String coordinate) {
            String[] parts = coordinate.split(":");
            if (parts.length < 3) {
                throw new IllegalArgumentException("Invalid coordinate format. Expected: groupId:artifactId:version[:packaging[:classifier]]");
            }
            this.groupId = parts[0];
            this.artifactId = parts[1];
            this.version = parts[2];
            this.packaging = parts.length > 3 ? parts[3] : null;
            this.classifier = parts.length > 4 ? parts[4] : null;
            this.isSnapshot = version.endsWith("-SNAPSHOT");
        }

        /**
         * 获取仓库路径（目录）
         * SNAPSHOT 版本使用 -SNAPSHOT 作为目录名
         * 例如: me/lucko/spark-api/0.1-SNAPSHOT/
         */
        public String getPath() {
            return groupId.replace('.', '/') + "/" + artifactId + "/" + getBaseVersion();
        }

        /**
         * 获取基础版本（用于路径）
         * SNAPSHOT 版本返回 -SNAPSHOT 形式
         * 例如: 0.1-SNAPSHOT
         */
        public String getBaseVersion() {
            return version;  // 保持原始版本（包含 -SNAPSHOT）
        }

        /**
         * 获取实际版本（用于文件名）
         * SNAPSHOT 版本返回时间戳版本
         * 例如: 0.1-20240720.200737-2
         */
        public String getActualVersion() {
            return snapshotVersion != null ? snapshotVersion : version;
        }

        public String getFileExtension() {
            String packagingType = packaging != null ? packaging : "jar";
            String extension = PACKAGING_TO_EXTENSION.get(packagingType);

            if (extension != null) {
                return extension;
            }

            if (packagingType.contains("plugin") ||
                    packagingType.contains("bundle") ||
                    packagingType.contains("osgi")) {
                return "jar";
            }

            return packagingType;
        }

        /**
         * 获取文件名
         * 使用实际版本（SNAPSHOT 使用时间戳版本）
         * 例如: spark-api-0.1-20240720.200737-2.jar
         */
        public String getFileName() {
            StringBuilder sb = new StringBuilder(artifactId).append("-");
            sb.append(getActualVersion());  // 使用时间戳版本

            if (classifier != null && !classifier.isEmpty()) {
                sb.append("-").append(classifier);
            }

            sb.append(".").append(getFileExtension());
            return sb.toString();
        }

        /**
         * 获取 POM 文件名
         */
        public String getPomFileName() {
            return artifactId + "-" + getActualVersion() + ".pom";
        }

        /**
         * 获取完整的远程路径（包含文件名）
         * 正确处理 SNAPSHOT：路径用 -SNAPSHOT，文件名用时间戳
         * 例如: me/lucko/spark-api/0.1-SNAPSHOT/spark-api-0.1-20240720.200737-2.jar
         */
        public String getRemotePath() {
            return getPath() + "/" + getFileName();
        }

        /**
         * 获取 POM 的完整远程路径
         */
        public String getRemotePomPath() {
            return getPath() + "/" + getPomFileName();
        }

        @Override
        public String toString() {
            StringBuilder sb = new StringBuilder(groupId).append(":").append(artifactId).append(":").append(version);
            if (packaging != null) {
                sb.append(":").append(packaging);
                String extension = getFileExtension();
                if (!packaging.equals(extension)) {
                    sb.append(" (file: .").append(extension).append(")");
                }
            }
            if (classifier != null) sb.append(":").append(classifier);
            if (isSnapshot && snapshotVersion != null) {
                sb.append(" (").append(snapshotVersion).append(")");
            }
            return sb.toString();
        }
    }

    public static class DownloadOptions {
        Path outputPath;
        Path outputDirectory;
        Path customFileName;

        boolean overwrite;
        boolean tryAllRepositories;
        List<String> preferredRepos;
        boolean createDirectories;
        boolean fallbackToJar;

        public DownloadOptions() {
            this.overwrite = false;
            this.tryAllRepositories = true;
            this.preferredRepos = new ArrayList<>();
            this.createDirectories = true;
            this.fallbackToJar = true;
        }

        public static DownloadOptions defaults() {
            return new DownloadOptions();
        }

        public DownloadOptions outputPath(Path path) {
            this.outputPath = path;
            return this;
        }

        public DownloadOptions outputPath(String path) {
            this.outputPath = Paths.get(path);
            return this;
        }

        public DownloadOptions outputDirectory(Path dir) {
            this.outputDirectory = dir;
            return this;
        }

        public DownloadOptions outputDirectory(String dir) {
            this.outputDirectory = Paths.get(dir);
            return this;
        }

        public DownloadOptions fileName(Path name) {
            this.customFileName = name;
            return this;
        }

        public DownloadOptions fileName(String name) {
            this.customFileName = Paths.get(name);
            return this;
        }

        public DownloadOptions relativeTo(Path baseDir, String... pathElements) {
            Path path = baseDir;
            for (String element : pathElements) {
                path = path.resolve(element);
            }
            this.outputPath = path;
            return this;
        }

        public DownloadOptions path(PathBuilder builder) {
            this.outputPath = builder.build();
            return this;
        }

        public DownloadOptions overwrite(boolean overwrite) {
            this.overwrite = overwrite;
            return this;
        }

        public DownloadOptions tryAllRepositories(boolean tryAll) {
            this.tryAllRepositories = tryAll;
            return this;
        }

        public DownloadOptions preferRepository(String... repoIds) {
            this.preferredRepos.addAll(Arrays.asList(repoIds));
            return this;
        }

        public DownloadOptions createDirectories(boolean create) {
            this.createDirectories = create;
            return this;
        }

        public DownloadOptions fallbackToJar(boolean fallback) {
            this.fallbackToJar = fallback;
            return this;
        }
    }

    public static class PathBuilder {
        private Path path;

        private PathBuilder(Path initial) {
            this.path = initial;
        }

        public static PathBuilder start(String first) {
            return new PathBuilder(Paths.get(first));
        }

        public static PathBuilder start(Path first) {
            return new PathBuilder(first);
        }

        public static PathBuilder userHome() {
            return new PathBuilder(Paths.get(System.getProperty("user.home")));
        }

        public static PathBuilder currentDir() {
            return new PathBuilder(Paths.get("."));
        }

        public static PathBuilder tempDir() {
            return new PathBuilder(Paths.get(System.getProperty("java.io.tmpdir")));
        }

        public PathBuilder resolve(String other) {
            this.path = this.path.resolve(other);
            return this;
        }

        public PathBuilder resolve(Path other) {
            this.path = this.path.resolve(other);
            return this;
        }

        public PathBuilder parent() {
            this.path = this.path.getParent();
            return this;
        }

        public PathBuilder sibling(String other) {
            this.path = this.path.resolveSibling(other);
            return this;
        }

        public PathBuilder normalize() {
            this.path = this.path.normalize();
            return this;
        }

        public PathBuilder toAbsolute() {
            this.path = this.path.toAbsolutePath();
            return this;
        }

        public Path build() {
            return this.path;
        }
    }

    public static class DownloadResult {
        final Path filePath;
        final MavenRepository repository;
        final MavenCoordinate coordinate;
        final boolean fromCache;
        final long fileSize;

        public DownloadResult(Path filePath, MavenRepository repository,
                              MavenCoordinate coordinate, boolean fromCache) {
            this.filePath = filePath;
            this.repository = repository;
            this.coordinate = coordinate;
            this.fromCache = fromCache;

            long size = 0;
            try {
                if (Files.exists(filePath)) {
                    size = Files.size(filePath);
                }
            } catch (IOException e) {
                // ignore
            }
            this.fileSize = size;
        }

        public Path getFilePath() {
            return filePath;
        }

        public Path getAbsolutePath() {
            return filePath.toAbsolutePath();
        }

        public Path getParentDirectory() {
            return filePath.getParent();
        }

        public String getFileName() {
            return filePath.getFileName().toString();
        }

        public MavenRepository getRepository() {
            return repository;
        }

        public boolean isFromCache() {
            return fromCache;
        }

        public long getFileSize() {
            return fileSize;
        }

        @Override
        public String toString() {
            return String.format("Downloaded: %s\nFrom: %s\nTo: %s\nSize: %s\nCached: %s",
                    coordinate, repository, filePath.toAbsolutePath(), formatBytes(fileSize), fromCache);
        }

        private String formatBytes(long bytes) {
            if (bytes < 1024) return bytes + " B";
            int exp = (int) (Math.log(bytes) / Math.log(1024));
            char pre = "KMGTPE".charAt(exp - 1);
            return String.format("%.2f %sB", bytes / Math.pow(1024, exp), pre);
        }
    }

    private static class SnapshotMetadata {
        String timestamp;
        String buildNumber;
        String snapshotVersion;

        public SnapshotMetadata(String timestamp, String buildNumber) {
            this.timestamp = timestamp;
            this.buildNumber = buildNumber;
        }

        public String getSnapshotVersion(String baseVersion) {
            String versionWithoutSnapshot = baseVersion.replace("-SNAPSHOT", "");
            this.snapshotVersion = versionWithoutSnapshot + "-" + timestamp + "-" + buildNumber;
            return snapshotVersion;
        }
    }

    private static class PomInfo {
        String packaging;
        String classifier;
        Map<String, String> properties;

        public PomInfo() {
            this.properties = new HashMap<>();
        }
    }

    // ==================== 公共 API ====================

    public DownloadResult download(String coordinate) throws IOException {
        return download(coordinate, DownloadOptions.defaults());
    }

    public DownloadResult download(String coordinate, Path outputDir) throws IOException {
        return download(coordinate, DownloadOptions.defaults().outputDirectory(outputDir));
    }

    public DownloadResult download(String coordinate, String outputDir) throws IOException {
        return download(coordinate, DownloadOptions.defaults().outputDirectory(outputDir));
    }

    public DownloadResult download(String coordinate, Path outputDir, Path fileName) throws IOException {
        return download(coordinate, DownloadOptions.defaults()
                .outputDirectory(outputDir)
                .fileName(fileName));
    }

    public DownloadResult downloadTo(String coordinate, Path fullPath) throws IOException {
        return download(coordinate, DownloadOptions.defaults().outputPath(fullPath));
    }

    public DownloadResult download(String coordinate, DownloadOptions options) throws IOException {
        return download(new MavenCoordinate(coordinate), options);
    }

    /**
     * 核心下载方法 - 修复了 SNAPSHOT 路径问题
     */
    public DownloadResult download(MavenCoordinate coordinate, DownloadOptions options) throws IOException {
        logger.info("Resolving: " + coordinate.getBaseVersion());

        Path outputPath = determineOutputPath(coordinate, options);
        logger.info("Target path: " + outputPath.toAbsolutePath());

        if (Files.exists(outputPath) && !options.overwrite) {
            logger.info("Already exists: " + outputPath);
            return new DownloadResult(outputPath, null, coordinate, true);
        }

        List<MavenRepository> reposToTry = getRepositoriesToTry(coordinate, options);

        IOException lastException = null;

        for (MavenRepository repo : reposToTry) {
            try {
                logger.info("Trying repository: " + repo);

                // 解析 SNAPSHOT 版本
                if (coordinate.isSnapshot) {
                    resolveSnapshotVersion(coordinate, repo);
                }

                // 解析 packaging
                if (coordinate.packaging == null) {
                    PomInfo pomInfo = parsePom(coordinate, repo);
                    coordinate.packaging = pomInfo.packaging != null ? pomInfo.packaging : "jar";

                    String extension = coordinate.getFileExtension();
                    logger.info("Resolved packaging: " + coordinate.packaging +
                            " (file extension: ." + extension + ")");

                    if (coordinate.classifier == null && pomInfo.classifier != null) {
                        coordinate.classifier = pomInfo.classifier;
                    }
                }

                // 使用新的 getRemotePath() 方法
                String remotePath = coordinate.getRemotePath();
                String downloadUrl = repo.url + remotePath;

                logger.info("Downloading: " + downloadUrl);

                if (options.createDirectories) {
                    Files.createDirectories(outputPath.getParent());
                }

                try {
                    downloadFile(downloadUrl, outputPath);
                    logger.info("Downloaded to: " + outputPath.toAbsolutePath());
                    return new DownloadResult(outputPath, repo, coordinate, false);

                } catch (IOException e) {
                    if (options.fallbackToJar &&
                            !coordinate.getFileExtension().equals("jar") &&
                            coordinate.packaging != null) {

                        logger.info("Failed with ." + coordinate.getFileExtension() +
                                ", trying .jar fallback...");

                        String originalPackaging = coordinate.packaging;
                        coordinate.packaging = "jar";

                        String fallbackPath = coordinate.getRemotePath();
                        String fallbackUrl = repo.url + fallbackPath;

                        logger.info("Fallback URL: " + fallbackUrl);

                        try {
                            downloadFile(fallbackUrl, outputPath);
                            logger.info("Downloaded to: " + outputPath.toAbsolutePath() +
                                    " (using .jar fallback)");
                            return new DownloadResult(outputPath, repo, coordinate, false);
                        } catch (IOException fallbackException) {
                            coordinate.packaging = originalPackaging;
                            throw e;
                        }
                    }
                    throw e;
                }

            } catch (IOException e) {
                System.err.println("Failed from " + repo.id + ": " + e.getMessage());
                lastException = e;

                if (!options.tryAllRepositories) {
                    throw e;
                }
            }
        }

        throw new IOException("Failed to download from all repositories. Last error: " +
                (lastException != null ? lastException.getMessage() : "Unknown"));
    }

    public List<DownloadResult> downloadBatch(List<String> coordinates, DownloadOptions options) throws IOException {
        List<DownloadResult> results = new ArrayList<>();

        for (int i = 0; i < coordinates.size(); i++) {
            logger.info("\n[" + (i + 1) + "/" + coordinates.size() + "] Processing: " + coordinates.get(i));
            logger.info("=".repeat(80));

            try {
                DownloadResult result = download(coordinates.get(i), options);
                results.add(result);
            } catch (IOException e) {
                logger.error("Failed to download " + coordinates.get(i) + ": " + e.getMessage());
                throw e;
            }
        }

        return results;
    }

    public List<DownloadResult> downloadBatch(String... coordinates) throws IOException {
        return downloadBatch(Arrays.asList(coordinates), DownloadOptions.defaults());
    }

    // ==================== 辅助方法 ====================

    private Path determineOutputPath(MavenCoordinate coordinate, DownloadOptions options) {
        if (options.outputPath != null) {
            return options.outputPath;
        }

        if (options.outputDirectory != null && options.customFileName != null) {
            if (options.customFileName.isAbsolute()) {
                return options.customFileName;
            } else {
                return options.outputDirectory.resolve(options.customFileName);
            }
        }

        if (options.outputDirectory != null) {
            String fileName = coordinate.getFileName();
            return options.outputDirectory.resolve(fileName);
        }

        if (options.customFileName != null) {
            if (options.customFileName.isAbsolute()) {
                return options.customFileName;
            } else {
                return Paths.get("").toAbsolutePath().resolve(options.customFileName);
            }
        }

        return defaultLocalRepo.resolve(coordinate.getPath()).resolve(coordinate.getFileName());
    }

    private List<MavenRepository> getRepositoriesToTry(MavenCoordinate coordinate, DownloadOptions options) {
        List<MavenRepository> result = new ArrayList<>();

        if (!options.preferredRepos.isEmpty()) {
            for (String repoId : options.preferredRepos) {
                repositories.stream()
                        .filter(r -> r.id.equals(repoId) && r.supports(coordinate.isSnapshot))
                        .findFirst()
                        .ifPresent(result::add);
            }
        }

        repositories.stream()
                .filter(r -> r.supports(coordinate.isSnapshot))
                .filter(r -> !result.contains(r))
                .forEach(result::add);

        return result;
    }

    /**
     * 解析 SNAPSHOT 版本 - 修复了元数据路径
     */
    private void resolveSnapshotVersion(MavenCoordinate coordinate, MavenRepository repo) throws IOException {
        if (!coordinate.isSnapshot) return;

        // 元数据路径使用 -SNAPSHOT 版本
        String metadataPath = coordinate.getPath() + "/maven-metadata.xml";
        String metadataUrl = repo.url + metadataPath;

        logger.info("Fetching metadata: " + metadataUrl);

        try {
            String metadataXml = downloadString(metadataUrl);
            SnapshotMetadata metadata = parseSnapshotMetadata(metadataXml);

            if (metadata != null) {
                String snapshotVersion = metadata.getSnapshotVersion(coordinate.version);
                coordinate.snapshotVersion = snapshotVersion;
                coordinate.snapshotTimestamp = metadata.timestamp;
                coordinate.snapshotBuildNumber = metadata.buildNumber;

                logger.info("  Resolved snapshot version: " + snapshotVersion);
                logger.info("  Timestamp: " + metadata.timestamp);
                logger.info("  Build number: " + metadata.buildNumber);
            }
        } catch (IOException e) {
            logger.error("Warning: Failed to fetch snapshot metadata: " + e.getMessage());
        }
    }

    private SnapshotMetadata parseSnapshotMetadata(String metadataXml) {
        try {
            DocumentBuilderFactory factory = DocumentBuilderFactory.newInstance();
            factory.setFeature("http://apache.org/xml/features/disallow-doctype-decl", true);
            factory.setFeature("http://xml.org/sax/features/external-general-entities", false);
            factory.setFeature("http://xml.org/sax/features/external-parameter-entities", false);

            DocumentBuilder builder = factory.newDocumentBuilder();
            Document doc = builder.parse(new ByteArrayInputStream(metadataXml.getBytes()));

            NodeList snapshotNodes = doc.getElementsByTagName("snapshot");
            if (snapshotNodes.getLength() > 0) {
                Element snapshot = (Element) snapshotNodes.item(0);
                String timestamp = getElementText(snapshot, "timestamp");
                String buildNumber = getElementText(snapshot, "buildNumber");

                if (timestamp != null && buildNumber != null) {
                    return new SnapshotMetadata(timestamp, buildNumber);
                }
            }

            NodeList snapshotVersions = doc.getElementsByTagName("snapshotVersion");
            if (snapshotVersions.getLength() > 0) {
                Element latestSnapshot = (Element) snapshotVersions.item(0);
                String value = getElementText(latestSnapshot, "value");
                if (value != null) {
                    return extractSnapshotInfo(value);
                }
            }

        } catch (Exception e) {
            System.err.println("Failed to parse snapshot metadata: " + e.getMessage());
        }

        return null;
    }

    private SnapshotMetadata extractSnapshotInfo(String snapshotVersion) {
        int lastDash = snapshotVersion.lastIndexOf('-');
        if (lastDash > 0) {
            String buildNumber = snapshotVersion.substring(lastDash + 1);
            String remaining = snapshotVersion.substring(0, lastDash);

            int secondLastDash = remaining.lastIndexOf('-');
            if (secondLastDash > 0) {
                String timestamp = remaining.substring(secondLastDash + 1);
                return new SnapshotMetadata(timestamp, buildNumber);
            }
        }
        return null;
    }

    /**
     * 解析 POM 文件 - 使用正确的路径
     */
    private PomInfo parsePom(MavenCoordinate coordinate, MavenRepository repo) throws IOException {
        PomInfo pomInfo = new PomInfo();

        // 使用 getRemotePomPath() 获取正确的 POM 路径
        String remotePomPath = coordinate.getRemotePomPath();
        String pomUrl = repo.url + remotePomPath;

        logger.info("Downloading POM: " + pomUrl);

        try {
            String pomXml = downloadString(pomUrl);

            DocumentBuilderFactory factory = DocumentBuilderFactory.newInstance();
            factory.setFeature("http://apache.org/xml/features/disallow-doctype-decl", true);
            factory.setFeature("http://xml.org/sax/features/external-general-entities", false);
            factory.setFeature("http://xml.org/sax/features/external-parameter-entities", false);

            DocumentBuilder builder = factory.newDocumentBuilder();
            Document doc = builder.parse(new ByteArrayInputStream(pomXml.getBytes()));

            pomInfo.properties = parseProperties(doc, coordinate);

            NodeList packagingNodes = doc.getElementsByTagName("packaging");
            if (packagingNodes.getLength() > 0) {
                String packaging = packagingNodes.item(0).getTextContent().trim();
                pomInfo.packaging = resolveProperty(packaging, pomInfo.properties);
            }

            pomInfo.classifier = extractClassifierFromPom(doc, pomInfo.properties);

        } catch (Exception e) {
            logger.info("Failed to parse POM: " + e.getMessage());
            pomInfo.packaging = "jar";
        }

        return pomInfo;
    }

    private String extractClassifierFromPom(Document doc, Map<String, String> properties) {
        NodeList plugins = doc.getElementsByTagName("plugin");
        for (int i = 0; i < plugins.getLength(); i++) {
            Element plugin = (Element) plugins.item(i);
            String artifactId = getElementText(plugin, "artifactId");

            if ("maven-jar-plugin".equals(artifactId) || "maven-shade-plugin".equals(artifactId)) {
                String classifier = getElementText(plugin, "classifier");
                if (classifier != null) {
                    return resolveProperty(classifier, properties);
                }
            }
        }
        return null;
    }

    private Map<String, String> parseProperties(Document doc, MavenCoordinate coordinate) {
        Map<String, String> properties = new HashMap<>();

        properties.put("project.groupId", coordinate.groupId);
        properties.put("project.artifactId", coordinate.artifactId);
        properties.put("project.version", coordinate.getBaseVersion());

        NodeList propertiesNodes = doc.getElementsByTagName("properties");
        if (propertiesNodes.getLength() > 0) {
            Element propertiesElement = (Element) propertiesNodes.item(0);
            NodeList children = propertiesElement.getChildNodes();

            for (int i = 0; i < children.getLength(); i++) {
                if (children.item(i) instanceof Element prop) {
                    properties.put(prop.getTagName(), prop.getTextContent().trim());
                }
            }
        }

        return properties;
    }

    private String resolveProperty(String value, Map<String, String> properties) {
        if (value == null) return null;

        String result = value;
        for (Map.Entry<String, String> entry : properties.entrySet()) {
            result = result.replace("${" + entry.getKey() + "}", entry.getValue());
        }
        return result;
    }

    private String getElementText(Element parent, String tagName) {
        NodeList nodes = parent.getElementsByTagName(tagName);
        if (nodes.getLength() > 0) {
            return nodes.item(0).getTextContent().trim();
        }
        return null;
    }

    private void downloadFile(String urlString, Path destination) throws IOException {
        URL url = new URL(urlString);
        HttpURLConnection connection = (HttpURLConnection) url.openConnection();
        connection.setRequestMethod("GET");
        connection.setConnectTimeout(TIMEOUT);
        connection.setReadTimeout(TIMEOUT);

        int responseCode = connection.getResponseCode();
        if (responseCode != 200) {
            throw new IOException("HTTP " + responseCode + ": " + urlString);
        }

        try (InputStream in = new BufferedInputStream(connection.getInputStream());
             OutputStream out = new BufferedOutputStream(Files.newOutputStream(destination))) {

            byte[] buffer = new byte[BUFFER_SIZE];
            int bytesRead;

            while ((bytesRead = in.read(buffer)) != -1) {
                out.write(buffer, 0, bytesRead);
            }
        }
    }

    private String downloadString(String urlString) throws IOException {
        URL url = new URL(urlString);
        HttpURLConnection connection = (HttpURLConnection) url.openConnection();
        connection.setRequestMethod("GET");
        connection.setConnectTimeout(TIMEOUT);
        connection.setReadTimeout(TIMEOUT);

        int responseCode = connection.getResponseCode();
        if (responseCode != 200) {
            throw new IOException("HTTP " + responseCode);
        }

        try (BufferedReader reader = new BufferedReader(
                new InputStreamReader(connection.getInputStream()))) {
            return reader.lines().collect(Collectors.joining("\n"));
        }
    }

    private String formatBytes(long bytes) {
        if (bytes < 1024) return bytes + " B";
        int exp = (int) (Math.log(bytes) / Math.log(1024));
        char pre = "KMGTPE".charAt(exp - 1);
        return String.format("%.2f %sB", bytes / Math.pow(1024, exp), pre);
    }

    public void addRepository(MavenRepository repository) {
        repositories.add(repository);
    }

    public void addRepository(String id, String url) {
        repositories.add(new MavenRepository(id, url));
    }

    public void removeRepository(String id) {
        repositories.removeIf(r -> r.id.equals(id));
    }

    public List<MavenRepository> getRepositories() {
        return new ArrayList<>(repositories);
    }
}
