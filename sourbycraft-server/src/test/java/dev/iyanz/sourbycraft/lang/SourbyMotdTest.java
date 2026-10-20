package dev.iyanz.sourbycraft.lang;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.iyanz.sourbycraft.SourbyCraftConfigTesting;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.serializer.plain.PlainTextComponentSerializer;
import org.junit.jupiter.api.Test;

/**
 * {@code messages.motd} is seeded and documented as applied at startup, so something must read it.
 * {@link SourbyMotd#apply} is that reader; the boot stage is checked in {@code SourbyCraftBootstrapTest}.
 */
class SourbyMotdTest {

    private static final PlainTextComponentSerializer PLAIN = PlainTextComponentSerializer.plainText();

    @Test
    void aConfiguredVariantIsHandedToTheServer() throws Exception {
        SourbyCraftConfigTesting.withConfig(Map.of("messages.motd", List.of("only variant")), () -> {
            final List<Component> sink = new ArrayList<>();
            assertTrue(SourbyMotd.apply(sink::add));
            assertEquals(1, sink.size());
            assertEquals("only variant", PLAIN.serialize(sink.get(0)));
        });
    }

    @Test
    void anAbsentKeyAppliesOneOfTheBuiltInVariants() throws Exception {
        SourbyCraftConfigTesting.withConfig(Map.of(), () -> {
            final List<Component> sink = new ArrayList<>();
            assertTrue(SourbyMotd.apply(sink::add));
            assertEquals(1, sink.size());
            final List<String> plainDefaults = SourbyMessages.defaultsFor(SourbyMessages.MOTD).stream()
                .map(raw -> PLAIN.serialize(net.kyori.adventure.text.minimessage.MiniMessage.miniMessage().deserialize(raw)))
                .toList();
            assertTrue(plainDefaults.contains(PLAIN.serialize(sink.get(0))), PLAIN.serialize(sink.get(0)));
        });
    }

    @Test
    void anExplicitEmptyListLeavesTheServerPropertiesMotdAlone() throws Exception {
        SourbyCraftConfigTesting.withConfig(Map.of("messages.motd", List.of()), () -> {
            final List<Component> sink = new ArrayList<>();
            assertFalse(SourbyMotd.apply(sink::add));
            assertTrue(sink.isEmpty(), "motd = [] must not override server.properties");
        });
    }

    @Test
    void aBlankOnlyListIsTreatedLikeAnEmptyList() throws Exception {
        SourbyCraftConfigTesting.withConfig(Map.of("messages.motd", List.of("  ", "")), () -> {
            final List<Component> sink = new ArrayList<>();
            assertFalse(SourbyMotd.apply(sink::add));
            assertTrue(sink.isEmpty());
        });
    }
}
