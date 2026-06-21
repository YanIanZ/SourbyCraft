package dev.iyanz.sourbycraft.install;

import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.file.Files;
import java.nio.file.Path;
import static org.junit.jupiter.api.Assertions.*;

class PluginDownloaderTest {

    private HttpServer server;

    @BeforeEach
    void startServer() throws IOException {
        server = HttpServer.create(new InetSocketAddress(0), 0);
        server.createContext("/spark.jar", ex -> {
            byte[] body = "fake-jar-bytes".getBytes();
            ex.sendResponseHeaders(200, body.length);
            ex.getResponseBody().write(body);
            ex.getResponseBody().close();
        });
        server.start();
    }

    @AfterEach
    void stopServer() { server.stop(0); }

    @Test
    void downloadsCiUrlToFile(@TempDir Path dir) throws IOException {
        int port = server.getAddress().getPort();
        var entry = new PluginEntry("spark", "ci", null,
            "http://localhost:" + port + "/spark.jar", null, null);
        Path target = PluginDownloader.download(entry, dir);
        assertNotNull(target);
        assertTrue(Files.exists(target));
        assertEquals("fake-jar-bytes", Files.readString(target));
    }

    @Test
    void returnsNullOnHttpError(@TempDir Path dir) throws IOException {
        int port = server.getAddress().getPort();
        var entry = new PluginEntry("404", "ci", null,
            "http://localhost:" + port + "/missing.jar", null, null);
        assertNull(PluginDownloader.download(entry, dir));
    }

    @Test
    void resolvesJenkinsArtifactMatchingGlob(@TempDir Path dir) throws IOException {
        int port = server.getAddress().getPort();
        server.createContext("/job/spark/lastSuccessfulBuild/api/json", ex -> {
            byte[] body = ("{\"artifacts\":[" +
                "{\"fileName\":\"spark-1.10-other.jar\",\"relativePath\":\"out/spark-1.10-other.jar\"}," +
                "{\"fileName\":\"spark-1.10-bukkit.jar\",\"relativePath\":\"out/spark-1.10-bukkit.jar\"}" +
                "]}").getBytes();
            ex.sendResponseHeaders(200, body.length);
            ex.getResponseBody().write(body);
            ex.getResponseBody().close();
        });
        // HttpServer matches longest-prefix context; an exact path "/.../artifact/out/spark-1.10-bukkit.jar"
        // would conflict with a parent context if one existed. Use the parent path so the lookup is robust.
        server.createContext("/job/spark/lastSuccessfulBuild/artifact/", ex -> {
            byte[] body = "spark-bukkit-bytes".getBytes();
            ex.sendResponseHeaders(200, body.length);
            ex.getResponseBody().write(body);
            ex.getResponseBody().close();
        });
        var entry = new PluginEntry("spark", "jenkins", null,
            "http://localhost:" + port + "/job/spark/lastSuccessfulBuild", null, "spark-*-bukkit.jar");
        Path target = PluginDownloader.download(entry, dir);
        assertNotNull(target);
        assertTrue(Files.exists(target));
        assertEquals("spark-bukkit-bytes", Files.readString(target));
    }

    @Test
    void resolvesJenkinsFirstArtifactWhenGlobNull(@TempDir Path dir) throws IOException {
        int port = server.getAddress().getPort();
        server.createContext("/job/spark2/lastSuccessfulBuild/api/json", ex -> {
            byte[] body = ("{\"artifacts\":[" +
                "{\"fileName\":\"only.jar\",\"relativePath\":\"only.jar\"}" +
                "]}").getBytes();
            ex.sendResponseHeaders(200, body.length);
            ex.getResponseBody().write(body);
            ex.getResponseBody().close();
        });
        server.createContext("/job/spark2/lastSuccessfulBuild/artifact/only.jar", ex -> {
            byte[] body = "only-bytes".getBytes();
            ex.sendResponseHeaders(200, body.length);
            ex.getResponseBody().write(body);
            ex.getResponseBody().close();
        });
        var entry = new PluginEntry("spark2", "jenkins", null,
            "http://localhost:" + port + "/job/spark2/lastSuccessfulBuild", null, null);
        Path target = PluginDownloader.download(entry, dir);
        assertNotNull(target);
        assertEquals("only-bytes", Files.readString(target));
    }

    @Test
    void returnsNullWhenJenkinsApiFails(@TempDir Path dir) throws IOException {
        int port = server.getAddress().getPort();
        var entry = new PluginEntry("missing", "jenkins", null,
            "http://localhost:" + port + "/job/missing/lastSuccessfulBuild", null, null);
        assertNull(PluginDownloader.download(entry, dir));
    }

    @Test
    void returnsNullWhenNoArtifactMatchesGlob(@TempDir Path dir) throws IOException {
        int port = server.getAddress().getPort();
        server.createContext("/job/nope/lastSuccessfulBuild/api/json", ex -> {
            byte[] body = ("{\"artifacts\":[" +
                "{\"fileName\":\"random.jar\",\"relativePath\":\"random.jar\"}" +
                "]}").getBytes();
            ex.sendResponseHeaders(200, body.length);
            ex.getResponseBody().write(body);
            ex.getResponseBody().close();
        });
        var entry = new PluginEntry("nope", "jenkins", null,
            "http://localhost:" + port + "/job/nope/lastSuccessfulBuild", null, "missing-*.jar");
        assertNull(PluginDownloader.download(entry, dir));
    }
}
