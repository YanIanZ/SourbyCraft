package dev.iyanz.sourbycraft.util;

import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.minimessage.MiniMessage;
import net.kyori.adventure.text.serializer.legacy.LegacyComponentSerializer;

/**
 * Defensive parser for operator-supplied text that may contain any mix of
 * legacy {@code &}/{@code §} colour codes, Adventure-style {@code &#RRGGBB}
 * or {@code §x§R§R§G§G§B§B} hex sequences, MiniMessage tags such as
 * {@code <red>} / {@code <gradient:#A:#B>} / {@code <rainbow>}, and raw
 * unicode emoji glyphs.
 *
 * <p>The aim is to never throw on malformed input. MiniMessage parse errors
 * (orphan {@code <}, unknown tag, etc.) fall back to legacy parsing; if that
 * also fails we return a plain {@link Component#text(String)} so the
 * surrounding command panel still emits something rather than blowing up.
 */
public final class TextRender {

    private static final MiniMessage MINI = MiniMessage.miniMessage();
    // hexColors() lets the deserializer accept &#RRGGBB short-form in addition
    // to the classic § escape sequence Bukkit emits.
    private static final LegacyComponentSerializer LEGACY = LegacyComponentSerializer.builder()
            .character('&')
            .hexColors()
            .useUnusualXRepeatedCharacterHexFormat()
            .build();

    private TextRender() {}

    /**
     * Strip Unicode variation selectors (U+FE0E text-style, U+FE0F emoji-style)
     * + zero-width joiner (U+200D) + tag chars (U+E0020..U+E007F). Vanilla MC
     * font has no glyph for these → renders as boxes like the `VS16` you'll
     * see next to `🏝️`. Drops them silently. The underlying base glyph (if
     * supported) renders unchanged.
     */
    public static String stripUnsupportedCombiners(String raw) {
        if (raw == null || raw.isEmpty()) return raw;
        StringBuilder out = new StringBuilder(raw.length());
        for (int i = 0; i < raw.length(); ) {
            int cp = raw.codePointAt(i);
            int charCount = Character.charCount(cp);
            boolean drop =
                    cp == 0xFE0E || cp == 0xFE0F                      // variation selectors
                    || cp == 0x200D                                  // ZWJ
                    || (cp >= 0xE0020 && cp <= 0xE007F)             // tag characters
                    || (cp >= 0xFE00 && cp <= 0xFE0F);              // variation selector range
            if (!drop) out.appendCodePoint(cp);
            i += charCount;
        }
        return out.toString();
    }

    /** Parse {@code raw} into a {@link Component}. Never throws. */
    public static Component parse(String raw) {
        if (raw == null || raw.isEmpty()) return Component.empty();
        raw = stripUnsupportedCombiners(raw);
        // Heuristic: if the string looks like MiniMessage (any '<tag>' pair)
        // try MiniMessage first; otherwise jump straight to legacy. Either
        // path falls back to plain text on failure.
        boolean looksLikeMiniMessage = raw.indexOf('<') >= 0 && raw.indexOf('>') > raw.indexOf('<');
        if (looksLikeMiniMessage) {
            try {
                return MINI.deserialize(raw);
            } catch (Throwable ignored) {
                // fall through
            }
        }
        try {
            return LEGACY.deserialize(raw);
        } catch (Throwable ignored) {
            return Component.text(raw);
        }
    }

    /**
     * Parse {@code raw} into a {@link Component} but fall back to
     * {@code fallback} (NOT plain-text raw) on any failure. Useful when the
     * caller already has a sensible pre-built component to show.
     */
    public static Component parseOr(String raw, Component fallback) {
        if (raw == null || raw.isEmpty()) return fallback;
        Component parsed = parse(raw);
        return parsed == Component.empty() ? fallback : parsed;
    }
}
