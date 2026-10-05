package dev.iyanz.sourbycraft.command;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

public class PluginsCommandTest {

    @Test
    void aFailedPluginIsListedByNameWithoutItsVersion() {
        assertEquals("EssentialsX", PluginsCommand.failureName("EssentialsX-2.22.1-dev+26-2dbd63a.jar"));
        assertEquals("ViaVersion", PluginsCommand.failureName("ViaVersion-5.12.0.jar"));
        assertEquals("EconomyShopGUI-Premium", PluginsCommand.failureName("EconomyShopGUI-Premium-6.4.1.jar"));
        assertEquals("Vault", PluginsCommand.failureName("Vault.jar"));
        assertEquals("MyPlugin", PluginsCommand.failureName("MyPlugin_v1.0.jar"));
    }

    @Test
    void aNameThatIsOnlyAVersionIsKeptRatherThanEmptied() {
        assertEquals("1.0", PluginsCommand.failureName("1.0.jar"));
    }

    @Test
    void plIsTheSameCommand() {
        assertTrue(new PluginsCommand("plugins").getAliases().contains("pl"));
    }
}
