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
        if (!EmojiShortcodes.enabled()) return;
        Component before = event.message();
        Component after = replace(before);
        if (after != before) {
            event.message(after);
        }
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
