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
}
