package dev.iyanz.sourbycraft.bootstrap;

import java.util.List;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class PluginProvisionerPinTest {
    @Test void metadataProjectTracksPinWhenPinOrderChanges() throws Exception {
        var field = PluginProvisioner.class.getDeclaredField("PINS");
        field.setAccessible(true);
        @SuppressWarnings("unchecked")
        var pins = (List<PluginProvisioner.Pin>) field.get(null);
        var reordered = PluginProvisioner.viaPins(List.of(pins.get(1), pins.get(0)));
        assertEquals("ViaBackwards-5.10.0.jar", reordered.get(0).filename());
        assertEquals("NpvuJQoq", reordered.get(0).projectId());
        assertEquals("ViaVersion-5.10.0.jar", reordered.get(1).filename());
        assertEquals("P1OZGk5p", reordered.get(1).projectId());
    }
    @Test void addedPinUsesItsOwnMetadataProject() {
        var extra = new PluginProvisioner.Pin("ExtraVia", "ExtraVia-", "ExtraVia.jar",
            "https://cdn.modrinth.com/extra.jar", "0".repeat(64), 123L, null, "extra-project");
        var via = PluginProvisioner.viaPins(List.of(extra)).getFirst();
        assertEquals("extra-project", via.projectId());
        assertEquals("https://cdn.modrinth.com/extra.jar", via.url());
    }

}
