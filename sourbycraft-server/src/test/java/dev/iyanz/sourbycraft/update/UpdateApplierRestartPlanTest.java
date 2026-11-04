package dev.iyanz.sourbycraft.update;

import dev.iyanz.sourbycraft.update.UpdateApplier.RestartPlan;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * Restart-mode resolution (audit SEC-9). {@code exit} resolves to {@link RestartPlan#EXIT_70},
 * which {@code UpdateApplier.restart} delivers by setting {@code MinecraftServer.abnormalExit}
 * before a clean {@code Bukkit.shutdown()} — the code 70 then comes from
 * {@code DedicatedServer.onServerExit()} after the shutdown has completed, not from a shutdown
 * hook calling {@code Runtime.halt}. The Bukkit half cannot run in a unit test; the mapping can.
 */
class UpdateApplierRestartPlanTest {

    @Test
    void exitModeIsACleanShutdownWithCode70() {
        assertEquals(RestartPlan.EXIT_70, RestartPlan.resolve("exit", false));
        assertEquals(RestartPlan.EXIT_70, RestartPlan.resolve(" Exit ", true));
    }

    @Test
    void autoPicksExit70OnlyInsideAPanelContainer() {
        assertEquals(RestartPlan.EXIT_70, RestartPlan.resolve("auto", true));
        assertEquals(RestartPlan.STOP, RestartPlan.resolve("auto", false));
        assertEquals(RestartPlan.STOP, RestartPlan.resolve(null, false));
    }

    @Test
    void stopAndSpigotAreExplicit() {
        // spigot is a clean stop: Spigot's restart command is disabled under region threading.
        assertEquals(RestartPlan.STOP, RestartPlan.resolve("stop", true));
        assertEquals(RestartPlan.STOP, RestartPlan.resolve("spigot", true));
        assertEquals(RestartPlan.STOP, RestartPlan.resolve("nonsense", true));
    }
}
