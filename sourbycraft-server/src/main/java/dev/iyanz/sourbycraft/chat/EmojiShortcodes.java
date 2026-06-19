package dev.iyanz.sourbycraft.chat;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.regex.Pattern;

/**
 * Registry of {@code :name:} → glyph emoji shortcodes. The default set
 * covers the most common Slack/Discord-style shortcuts; operators can add
 * or override entries via {@code emoji.shortcodes} in {@code sourbycraft.yml}.
 *
 * <p>The matching pattern intentionally allows letters, digits, underscore,
 * plus, and minus so codes like {@code :+1:} and {@code :-1:} work.
 */
public final class EmojiShortcodes {

    public static final Pattern PATTERN = Pattern.compile(":([a-zA-Z0-9_+-]+):");
    private static volatile Map<String, String> MAP = defaults();
    private static volatile boolean enabled = true;

    private EmojiShortcodes() {}

    public static boolean enabled() {
        return enabled;
    }

    public static void setEnabled(boolean v) {
        enabled = v;
    }

    public static Map<String, String> map() {
        return MAP;
    }

    public static String lookup(String shortcode) {
        return MAP.get(shortcode);
    }

    public static synchronized void replaceAll(Map<String, String> custom) {
        Map<String, String> merged = new LinkedHashMap<>(defaults());
        if (custom != null) merged.putAll(custom);
        MAP = Collections.unmodifiableMap(merged);
    }

    private static Map<String, String> defaults() {
        Map<String, String> m = new LinkedHashMap<>();
        m.put("smile", "😀");
        m.put("grin", "😁");
        m.put("laughing", "😂");
        m.put("rofl", "🤣");
        m.put("wink", "😉");
        m.put("blush", "😊");
        m.put("cool", "😎");
        m.put("thinking", "🤔");
        m.put("cry", "😢");
        m.put("sob", "😭");
        m.put("rage", "😡");
        m.put("heart", "❤");
        m.put("broken_heart", "💔");
        m.put("blue_heart", "💙");
        m.put("green_heart", "💚");
        m.put("yellow_heart", "💛");
        m.put("purple_heart", "💜");
        m.put("fire", "🔥");
        m.put("star", "⭐");
        m.put("sparkles", "✨");
        m.put("zap", "⚡");
        m.put("boom", "💥");
        m.put("ok_hand", "👌");
        m.put("+1", "👍");
        m.put("-1", "👎");
        m.put("clap", "👏");
        m.put("pray", "🙏");
        m.put("wave", "👋");
        m.put("muscle", "💪");
        m.put("eyes", "👀");
        m.put("100", "💯");
        m.put("check", "✅");
        m.put("x", "❌");
        m.put("warning", "⚠");
        m.put("question", "❓");
        m.put("exclamation", "❗");
        m.put("crown", "👑");
        m.put("gem", "💎");
        m.put("money", "💰");
        m.put("gift", "🎁");
        m.put("rocket", "🚀");
        m.put("trophy", "🏆");
        m.put("medal", "🏅");
        m.put("skull", "💀");
        m.put("ghost", "👻");
        m.put("alien", "👽");
        m.put("robot", "🤖");
        m.put("sun", "☀");
        m.put("moon", "🌙");
        m.put("rainbow", "🌈");
        m.put("snowflake", "❄");
        return Collections.unmodifiableMap(m);
    }
}
