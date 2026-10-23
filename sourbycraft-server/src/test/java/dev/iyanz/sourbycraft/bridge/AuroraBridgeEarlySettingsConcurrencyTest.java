package dev.iyanz.sourbycraft.bridge;

import static org.junit.jupiter.api.Assertions.assertNotNull;

import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;

class AuroraBridgeEarlySettingsConcurrencyTest {

    @Test
    void earlySettingsDoesNotWaitForTheRuntimeMonitor() throws Exception {
        // ThreadChecks initializes by reading earlySettings. runtime() holds the class monitor before
        // invoking ThreadChecks, so waiting on that monitor here can deadlock class initialization.
        try (AutoCloseable reset = AuroraBridge.resetRuntimeForTesting()) {
            final CompletableFuture<dev.iyanz.sourbycraft.config.AuroraConfig.Bridge> settings = new CompletableFuture<>();
            final Thread reader = new Thread(() -> {
                try {
                    settings.complete(AuroraBridge.earlySettings());
                } catch (Throwable failure) {
                    settings.completeExceptionally(failure);
                }
            }, "early-bridge-settings-test");
            try {
                synchronized (AuroraBridge.class) {
                    reader.start();
                    assertNotNull(settings.get(2, TimeUnit.SECONDS),
                        "early settings must be readable while another thread initializes the runtime");
                }
            } finally {
                reader.join(5_000);
            }
        }
    }
}
