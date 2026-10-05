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

    @Test
    void v23AliasesClaimTheSameCommandAndPreserveNamespacedBuiltins() {
        var map = org.mockito.Mockito.mock(org.bukkit.command.CommandMap.class);
        var known = new java.util.HashMap<String, org.bukkit.command.Command>();
        var builtin = org.mockito.Mockito.mock(org.bukkit.command.Command.class);
        known.put("plugins", builtin);
        known.put("pl", builtin);
        known.put("bukkit:plugins", builtin);
        known.put("bukkit:pl", builtin);
        org.mockito.Mockito.when(map.getKnownCommands()).thenReturn(known);
        var command = new PluginsCommand("plugins");
        SourbyCraftCommands.claim(map, command);
        org.junit.jupiter.api.Assertions.assertSame(command, known.get("plugins"));
        org.junit.jupiter.api.Assertions.assertSame(command, known.get("pl"));
        org.junit.jupiter.api.Assertions.assertSame(command, known.get("sourbycraft:pl"));
        org.junit.jupiter.api.Assertions.assertSame(builtin, known.get("bukkit:pl"));
        org.junit.jupiter.api.Assertions.assertSame(builtin, known.get("bukkit:plugins"));
    }

    @Test
    void v23SearchAndFilterUseStableSortedPages() {
        var rows = java.util.List.of(
            new PluginsCommand.Row("Zulu", dev.iyanz.sourbycraft.bridge.CompatibilityState.NATIVE, "native", ""),
            new PluginsCommand.Row("alpha", dev.iyanz.sourbycraft.bridge.CompatibilityState.DISABLED, "disabled", ""),
            new PluginsCommand.Row("Beta", dev.iyanz.sourbycraft.bridge.CompatibilityState.FAILED, "failed", "Beta-1.0.jar"));
        var all = PluginsCommand.parse(new String[]{});
        assertEquals(java.util.List.of("alpha", "Beta", "Zulu"),
            PluginsCommand.select(rows, all).stream().map(PluginsCommand.Row::name).toList());
        assertEquals("Beta", PluginsCommand.select(rows, PluginsCommand.parse(new String[]{"search", "bEt"})).getFirst().name());
        assertEquals(1, PluginsCommand.select(rows, PluginsCommand.parse(new String[]{"filter", "disabled"})).size());
        var page = PluginsCommand.render(rows, PluginsCommand.parse(new String[]{"2"}), 2);
        var serializer = net.kyori.adventure.text.serializer.plain.PlainTextComponentSerializer.plainText();
        var output = page.stream().map(serializer::serialize).collect(java.util.stream.Collectors.joining("\n"));
        assertTrue(output.contains("Page 2/2"));
        assertTrue(output.contains("Zulu"));
        org.junit.jupiter.api.Assertions.assertFalse(output.contains("Beta-1.0"));
        org.junit.jupiter.api.Assertions.assertFalse(output.contains("alpha"));
        assertTrue(output.contains("Disabled does not mean failed"));
        org.junit.jupiter.api.Assertions.assertThrows(IllegalArgumentException.class,
            () -> PluginsCommand.parse(new String[]{"0"}));
        org.junit.jupiter.api.Assertions.assertThrows(IllegalArgumentException.class,
            () -> PluginsCommand.parse(new String[]{"filter", "invalid"}));
        var pastEnd = PluginsCommand.render(rows, PluginsCommand.parse(new String[]{"2147483647"}), 1);
        assertTrue(pastEnd.stream().map(serializer::serialize).anyMatch(line -> line.contains("Page does not exist")));
    }
}
