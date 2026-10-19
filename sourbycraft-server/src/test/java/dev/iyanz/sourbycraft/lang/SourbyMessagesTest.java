package dev.iyanz.sourbycraft.lang;

import com.electronwill.nightconfig.core.file.CommentedFileConfig;
import dev.iyanz.sourbycraft.SourbyCraftConfig;
import dev.iyanz.sourbycraft.config.ConfigSnapshot;
import java.lang.reflect.Constructor;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.minimessage.MiniMessage;
import net.kyori.adventure.text.minimessage.tag.resolver.Placeholder;
import net.kyori.adventure.text.serializer.plain.PlainTextComponentSerializer;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * OBS-14 / TEST-COVERAGE-GAPS #5: the operator-editable message layer.
 *
 * <p>Messages come from {@code messages.<key>} in the unified TOML, which an operator can break.
 * Join, quit and login handlers call {@link SourbyMessages#get}, so it must never throw and never
 * render an empty line for a known key. {@link SourbyCraftConfig} has no test seam, so the
 * in-memory snapshot is swapped by reflection and restored (see {@link #withConfig}).</p>
 */
class SourbyMessagesTest {

    private static final PlainTextComponentSerializer PLAIN = PlainTextComponentSerializer.plainText();

    @Test
    void unknownKeyYieldsTheEmptyComponentAndZeroVariants() throws Exception {
        withConfig(Map.of(), () -> {
            assertSame(Component.empty(), SourbyMessages.get("no-such-key"));
            assertEquals("", SourbyMessages.getRaw("no-such-key"));
            assertEquals(0, SourbyMessages.variantCount("no-such-key"));
            assertTrue(SourbyMessages.defaultsFor("no-such-key").isEmpty());
        });
    }

    @Test
    void everyBuiltInVariantIsValidMiniMessageAndFillsThePlayerPlaceholder() {
        assertEquals(Set.of(SourbyMessages.SERVER_FULL, SourbyMessages.MOTD, SourbyMessages.JOIN, SourbyMessages.LEAVE),
            SourbyMessages.keys());
        for (final String key : SourbyMessages.keys()) {
            assertFalse(SourbyMessages.defaultsFor(key).isEmpty(), key);
            for (final String variant : SourbyMessages.defaultsFor(key)) {
                final String text = PLAIN.serialize(MiniMessage.miniMessage().deserialize(variant,
                    Placeholder.unparsed("player", "Steve")));
                assertFalse(text.isBlank(), variant);
                assertFalse(text.contains("<") || text.contains(">"), "unparsed tag left in: " + text);
                if (key.equals(SourbyMessages.JOIN) || key.equals(SourbyMessages.LEAVE)) {
                    assertTrue(text.contains("Steve"), "join/leave variant without <player>: " + variant);
                }
            }
        }
    }

    @Test
    void missingConfigFallsBackToTheBuiltInVariants() throws Exception {
        withConfig(Map.of(), () -> {
            for (final String key : SourbyMessages.keys()) {
                assertEquals(SourbyMessages.defaultsFor(key).size(), SourbyMessages.variantCount(key), key);
                assertTrue(SourbyMessages.defaultsFor(key).contains(SourbyMessages.getRaw(key)), key);
            }
        });
    }

    @Test
    void operatorListReplacesTheDefaultsAndSubstitutesThePlaceholder() throws Exception {
        withConfig(Map.of("messages.join", List.of("<green>Hai <player></green>")), () -> {
            assertEquals(1, SourbyMessages.variantCount(SourbyMessages.JOIN));
            assertEquals("Hai Alex", PLAIN.serialize(SourbyMessages.get(SourbyMessages.JOIN,
                Placeholder.unparsed("player", "Alex"))));
        });
    }

    @Test
    void placeholderValueIsInsertedLiterallyNotParsedAsMiniMessage() throws Exception {
        withConfig(Map.of("messages.join", List.of("<player> joined")), () -> {
            final Component message = SourbyMessages.get(SourbyMessages.JOIN,
                Placeholder.unparsed("player", "<red><click:run_command:'/op me'>x"));
            assertEquals("<red><click:run_command:'/op me'>x joined", PLAIN.serialize(message));
            assertNoClickEvent(message);
        });
    }

    @Test
    void blankAndNonStringEntriesAreSkipped() throws Exception {
        final List<Object> variants = new ArrayList<>(Arrays.asList("", "   ", 5, null, "<b>Only</b>"));
        withConfig(Map.of("messages.leave", variants), () -> {
            assertEquals(1, SourbyMessages.variantCount(SourbyMessages.LEAVE));
            for (int i = 0; i < 50; i++) {
                assertEquals("Only", PLAIN.serialize(SourbyMessages.get(SourbyMessages.LEAVE)));
            }
        });
    }

    @Test
    void anEntirelyBlankOrWrongTypedValueFallsBackToDefaultsRatherThanSendingNothing() throws Exception {
        for (final Object value : new Object[] {List.of(), List.of("", " "), "a plain string", 3}) {
            withConfig(Map.of("messages.leave", value), () -> {
                assertEquals(SourbyMessages.defaultsFor(SourbyMessages.LEAVE).size(),
                    SourbyMessages.variantCount(SourbyMessages.LEAVE), String.valueOf(value));
                assertTrue(SourbyMessages.defaultsFor(SourbyMessages.LEAVE).contains(SourbyMessages.getRaw(SourbyMessages.LEAVE)));
            });
        }
    }

    @Test
    void malformedOperatorMiniMessageNeverThrows() throws Exception {
        final List<String> malformed = List.of("<red>unclosed", "</closeonly>", "<#GGGGGG>bad hex", "<<>>",
            "<gradient:>empty</gradient>", "<click:run_command>no value</click>", "<hover:show_text:'x'", "<");
        for (final String raw : malformed) {
            withConfig(Map.of("messages.server-full", List.of(raw)), () -> {
                final Component message = assertDoesNotThrow(() -> SourbyMessages.get(SourbyMessages.SERVER_FULL), raw);
                assertNotNull(message, raw);
            });
        }
    }

    @Test
    void eachCallPicksOneOfTheConfiguredVariants() throws Exception {
        withConfig(Map.of("messages.motd", List.of("alpha", "beta")), () -> {
            final Set<String> seen = new HashSet<>();
            for (int i = 0; i < 200; i++) {
                final String text = PLAIN.serialize(SourbyMessages.get(SourbyMessages.MOTD));
                assertTrue(text.equals("alpha") || text.equals("beta"), text);
                seen.add(text);
            }
            // 200 uniform draws over two variants: missing one has probability 2^-199.
            assertEquals(Set.of("alpha", "beta"), seen);
        });
    }

    @Test
    void seedingWritesEveryKeyOnceAndNeverClobbersAnOperatorEdit(@TempDir final Path dir) {
        final CommentedFileConfig file = CommentedFileConfig.of(dir.resolve("seed.toml"));
        try {
            file.set("messages.join", new ArrayList<>(List.of("operator <player>")));
            final boolean[] changed = {false};

            SourbyMessages.seedDefaults(file, changed);

            assertTrue(changed[0]);
            assertEquals(List.of("operator <player>"), file.get("messages.join"));
            for (final String key : List.of(SourbyMessages.SERVER_FULL, SourbyMessages.MOTD, SourbyMessages.LEAVE)) {
                assertEquals(SourbyMessages.defaultsFor(key), file.get("messages." + key), key);
            }
            assertNotNull(file.getComment("messages"));

            final boolean[] again = {false};
            SourbyMessages.seedDefaults(file, again);
            assertFalse(again[0], "second seed is a no-op");
        } finally {
            file.close();
        }
    }

    @Test
    void seedingANullFileIsANoOp() {
        final boolean[] changed = {false};
        assertDoesNotThrow(() -> SourbyMessages.seedDefaults(null, changed));
        assertFalse(changed[0]);
    }

    // ------------------------------------------------------------------------------------ helpers

    private static void assertNoClickEvent(final Component component) {
        assertEquals(null, component.clickEvent(), "click event leaked from a placeholder value");
        component.children().forEach(SourbyMessagesTest::assertNoClickEvent);
    }

    interface ThrowingRunnable {
        void run() throws Exception;
    }

    /**
     * Swaps {@code SourbyCraftConfig.loaded} for a snapshot of {@code values} while {@code action}
     * runs. A {@code SourbyCraftConfig.overrideForTesting(ConfigSnapshot)} seam would replace this.
     */
    static void withConfig(final Map<String, ?> values, final ThrowingRunnable action) throws Exception {
        final Field loaded = SourbyCraftConfig.class.getDeclaredField("loaded");
        loaded.setAccessible(true);
        final Object previous = loaded.get(null);
        final Class<?> record = loaded.getType();
        final Constructor<?> constructor = record.getDeclaredConstructors()[0];
        constructor.setAccessible(true);
        final Method aurora = record.getDeclaredMethod("aurora");
        aurora.setAccessible(true);
        @SuppressWarnings("unchecked")
        final Map<String, Object> typed = (Map<String, Object>) values;
        loaded.set(null, constructor.newInstance(new ConfigSnapshot(typed), aurora.invoke(previous)));
        try {
            action.run();
        } finally {
            loaded.set(null, previous);
        }
    }
}
