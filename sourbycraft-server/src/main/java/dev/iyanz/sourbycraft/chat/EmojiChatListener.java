package dev.iyanz.sourbycraft.chat;

import io.papermc.paper.event.player.AsyncChatEvent;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.TextReplacementConfig;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;

/**
 * Async chat listener that rewrites {@code :shortcode:} tokens to their
 * matching unicode glyph from {@link EmojiShortcodes}. Runs at MONITOR-pre
 * (HIGH priority + cancel-aware) so other chat plugins can still see the
 * post-translation text; deliberately not LOWEST because mention-parsers
 * usually want to scan the original {@code :name:} form first.
 */
public final class EmojiChatListener implements Listener {

    private static final TextReplacementConfig.Builder BASE = TextReplacementConfig.builder()
            .match(EmojiShortcodes.PATTERN);

    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onChat(AsyncChatEvent event) {
        Component before = event.message();
        Component after = before;
        if (EmojiShortcodes.enabled()) {
            after = replace(after);
        }
        // Strip unsupported Unicode combiners (VS15/VS16/ZWJ/tags). Vanilla MC
        // font renders these as missing-glyph boxes (the `VS16` annotation
        // visible next to 🏝️ in chat / signs). Done after shortcode replace
        // so glyphs we control are also stripped. Pure pass-through if input
        // has no combiners — String#equals fast path.
        Component stripped = stripCombiners(after);
        if (stripped != after) after = stripped;
        if (after != before) event.message(after);
    }

    private static Component stripCombiners(Component in) {
        TextReplacementConfig cfg = TextReplacementConfig.builder()
                .match(java.util.regex.Pattern.compile("[\\uFE00-\\uFE0F\\u200D]|[\\uDB40\\uDD00-\\uDB40\\uDDFF]"))
                .replacement((match, b) -> Component.empty())
                .build();
        return in.replaceText(cfg);
    }

    /** Public for command output / custom paths that want the same rewrite. */
    public static Component replace(Component input) {
        TextReplacementConfig cfg = BASE.replacement((match, builder) -> {
            String key = match.group(1);
            String glyph = EmojiShortcodes.lookup(key);
            return glyph == null
                    ? Component.text(match.group())   // unknown code: leave literal
                    : Component.text(glyph);
        }).build();
        return input.replaceText(cfg);
    }
}
