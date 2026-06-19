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

    /** Parse {@code raw} into a {@link Component}. Never throws. */
    public static Component parse(String raw) {
        if (raw == null || raw.isEmpty()) return Component.empty();
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
