package dev.iyanz.sourbycraft.testplugin;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class TestPluginDescriptorTest {

    @Test
    void fixtureDeclaresFoliaSupport() throws IOException {
        try (InputStream input = TestPluginDescriptorTest.class.getResourceAsStream("/paper-plugin.yml")) {
            assertNotNull(input);
            final String descriptor = new String(input.readAllBytes(), StandardCharsets.UTF_8);
            assertTrue(descriptor.lines().anyMatch("folia-supported: true"::equals));
        }
    }
}
