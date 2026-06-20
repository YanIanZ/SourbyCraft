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
        // VANILLA-MC-FONT-COMPATIBLE EMOJI ONLY.
        //
        // The vanilla Minecraft Java Edition unicode font (default.json + ascii
        // glyph pages) covers the Basic Multilingual Plane (BMP, U+0000–U+FFFF)
        // — Latin extended, dingbats, geometric shapes, arrows, miscellaneous
        // symbols, chess + card suits, zodiac. Supplementary Multilingual Plane
        // emoji (U+1F### range — smileys, food, animals, transport, etc.) are
        // NOT covered by the bundled font. They render as a "missing-glyph"
        // box in chat + signs unless the operator installs a custom emoji
        // resource pack.
        //
        // The previous (extended) set included many U+1F### codepoints which
        // showed up as boxes on standard clients. This default set is
        // BMP-only — every glyph below is U+FFFF or below + verified to ship
        // in the vanilla unicode page sheet.
        //
        // Operators with custom emoji resource packs can register the full
        // smiley/food/animal set via `emoji.shortcodes` in sourbycraft.yml.

        // Hearts (dingbats)
        m.put("heart", "❤");        // ❤
        m.put("white_heart", "♡");  // ♡
        m.put("spade", "♠");        // ♠
        m.put("club", "♣");         // ♣
        m.put("diamond", "♦");      // ♦

        // Stars + sparkles (dingbats)
        m.put("star", "★");          // ★
        m.put("white_star", "☆");    // ☆
        m.put("sparkle", "❇");       // ❇
        m.put("snowflake", "❄");     // ❄
        m.put("snowman", "☃");       // ☃
        m.put("snowman_no_snow", "⛄");// ⛄

        // Weather + sun (misc symbols)
        m.put("sun", "☀");           // ☀
        m.put("cloud", "☁");         // ☁
        m.put("umbrella", "☂");      // ☂
        m.put("umbrella_rain", "☔"); // ☔
        m.put("hot_drink", "☕");     // ☕
        m.put("shamrock", "☘");      // ☘
        m.put("comet", "☄");         // ☄
        m.put("aquarius", "♒");      // ♒

        // Action / warning (dingbats)
        m.put("check", "✅");         // ✅
        m.put("checkmark", "✓");     // ✓
        m.put("heavy_check", "✔");   // ✔
        m.put("x", "✗");             // ✗
        m.put("heavy_x", "✘");       // ✘
        m.put("cross", "✖");         // ✖
        m.put("warning", "⚠");       // ⚠
        m.put("question", "❓");      // ❓
        m.put("white_question", "❔");// ❔
        m.put("exclamation", "❗");   // ❗
        m.put("white_exclamation", "❕"); // ❕
        m.put("double_exclamation", "‼"); // ‼
        m.put("interrobang", "⁉");    // ⁉
        m.put("noentry", "⛔");        // ⛔
        m.put("biohazard", "☣");      // ☣
        m.put("radioactive", "☢");    // ☢

        // Hands / faces (BMP only — most "hand" emoji are U+1F### so excluded)
        m.put("v", "✌");              // ✌
        m.put("write", "✍");          // ✍
        m.put("fist_raised", "✊");    // ✊
        m.put("raised_hand", "✋");    // ✋
        m.put("smile", "☺");          // ☺
        m.put("frown", "☹");          // ☹

        // Religion / culture
        m.put("peace", "☮");          // ☮
        m.put("yin_yang", "☯");       // ☯
        m.put("cross_religious", "✝");// ✝
        m.put("star_of_david", "✡");  // ✡
        m.put("wheel_of_dharma", "☸");// ☸
        m.put("om", "ॐ");             // ॐ
        m.put("orthodox_cross", "☦"); // ☦

        // Music
        m.put("note", "♪");           // ♪
        m.put("notes", "♫");          // ♫
        m.put("beamed_notes", "♬");   // ♬
        m.put("flat", "♭");           // ♭
        m.put("sharp", "♯");          // ♯
        m.put("natural", "♮");        // ♮

        // Telephone / mail / signs
        m.put("phone", "☎");          // ☎
        m.put("mail", "✉");           // ✉
        m.put("scissors", "✂");       // ✂
        m.put("airplane", "✈");       // ✈
        m.put("anchor", "⚓");         // ⚓
        m.put("crown", "♛");          // ♛
        m.put("crown_white", "♔");    // ♔
        m.put("queen", "♛");          // ♛
        m.put("king", "♔");           // ♔
        m.put("knight", "♞");         // ♞
        m.put("rook", "♜");           // ♜
        m.put("bishop", "♝");         // ♝
        m.put("pawn", "♟");           // ♟

        // Currency
        m.put("euro", "€");           // €
        m.put("pound", "£");          // £
        m.put("yen", "¥");            // ¥
        m.put("cent", "¢");           // ¢
        m.put("dollar", "$");
        m.put("bitcoin", "₿");        // ₿
        m.put("rupee", "₹");          // ₹

        // Math / shapes (heavy in vanilla MC font)
        m.put("plus", "➕");           // ➕
        m.put("minus", "➖");          // ➖
        m.put("divide", "➗");         // ➗
        m.put("multiply", "×");       // ×
        m.put("infinity", "∞");       // ∞
        m.put("degree", "°");         // °
        m.put("micro", "µ");          // µ
        m.put("plusminus", "±");      // ±
        m.put("squared", "²");        // ²
        m.put("cubed", "³");          // ³
        m.put("approx", "≈");         // ≈
        m.put("notequal", "≠");       // ≠
        m.put("leq", "≤");            // ≤
        m.put("geq", "≥");            // ≥
        m.put("sqrt", "√");           // √
        m.put("integral", "∫");       // ∫
        m.put("sum", "∑");            // ∑
        m.put("delta", "Δ");          // Δ
        m.put("alpha", "α");          // α
        m.put("beta", "β");           // β
        m.put("gamma", "γ");          // γ
        m.put("pi", "π");             // π
        m.put("omega", "Ω");          // Ω
        m.put("therefore", "∴");      // ∴

        // Arrows
        m.put("arrow_up", "⬆");       // ⬆
        m.put("arrow_down", "⬇");     // ⬇
        m.put("arrow_left", "⬅");     // ⬅
        m.put("arrow_right", "➡");    // ➡
        m.put("arrow_up_left", "↖");  // ↖
        m.put("arrow_up_right", "↗"); // ↗
        m.put("arrow_down_right", "↘"); // ↘
        m.put("arrow_down_left", "↙"); // ↙
        m.put("left", "←");           // ←
        m.put("right", "→");          // →
        m.put("up", "↑");             // ↑
        m.put("down", "↓");           // ↓
        m.put("leftright", "↔");      // ↔
        m.put("updown", "↕");         // ↕
        m.put("return", "↩");         // ↩
        m.put("forward", "↪");        // ↪
        m.put("triangleright", "▶");  // ▶
        m.put("triangleleft", "◀");   // ◀
        m.put("triangleup", "▲");     // ▲
        m.put("triangledown", "▼");   // ▼

        // Geometric / blocks (great for sign art)
        m.put("square", "■");         // ■
        m.put("square_white", "□");   // □
        m.put("circle", "●");         // ●
        m.put("circle_white", "○");   // ○
        m.put("diamond_shape", "◆");  // ◆
        m.put("diamond_white", "◇");  // ◇
        m.put("dot_med", "•");        // •
        m.put("dot_lg", "●");         // ●
        m.put("bullet", "‣");         // ‣
        m.put("middle_dot", "·");     // ·
        m.put("ellipsis", "…");       // …
        m.put("section", "§");        // §
        m.put("para", "¶");           // ¶
        m.put("dagger", "†");         // †
        m.put("doubledagger", "‡");   // ‡
        m.put("anchor_punct", "⚓");   // ⚓

        // Zodiac (all BMP)
        m.put("aries", "♈");          // ♈
        m.put("taurus", "♉");         // ♉
        m.put("gemini", "♊");         // ♊
        m.put("cancer", "♋");         // ♋
        m.put("leo", "♌");            // ♌
        m.put("virgo", "♍");          // ♍
        m.put("libra", "♎");          // ♎
        m.put("scorpio", "♏");        // ♏
        m.put("sagittarius", "♐");    // ♐
        m.put("capricorn", "♑");      // ♑
        m.put("pisces", "♓");         // ♓

        // Misc symbols (all BMP)
        m.put("recycle", "♻");        // ♻
        m.put("male", "♂");           // ♂
        m.put("female", "♀");         // ♀
        m.put("trademark", "™");      // ™
        m.put("copyright", "©");      // ©
        m.put("registered", "®");     // ®
        m.put("ohm", "Ω");            // Ω
        m.put("permille", "‰");       // ‰
        m.put("dotop", "•");          // •

        // Box drawing (great for sign frames)
        m.put("box_h", "─");          // ─
        m.put("box_v", "│");          // │
        m.put("box_tl", "┌");         // ┌
        m.put("box_tr", "┐");         // ┐
        m.put("box_bl", "└");         // └
        m.put("box_br", "┘");         // ┘
        m.put("box_cross", "┼");      // ┼
        m.put("dbl_h", "═");          // ═
        m.put("dbl_v", "║");          // ║
        m.put("dbl_tl", "╔");         // ╔
        m.put("dbl_tr", "╗");         // ╗
        m.put("dbl_bl", "╚");         // ╚
        m.put("dbl_br", "╝");         // ╝
        m.put("block", "█");          // █
        m.put("lighter", "░");        // ░
        m.put("medium", "▒");         // ▒
        m.put("darker", "▓");         // ▓

        // Arrows in circle / dingbats
        m.put("arrow_redo", "↩");     // ↩
        m.put("loop", "↻");           // ↻
        m.put("anti_loop", "↺");      // ↺

        // Pixelated dingbats kept (most common BMP shortcuts)
        m.put("checkbox_unchecked", "☐"); // ☐
        m.put("checkbox_checked", "☑");   // ☑
        m.put("checkbox_x", "☒");         // ☒

        // Currency / numerals
        m.put("ordinal_m", "º");      // º
        m.put("ordinal_f", "ª");      // ª

        // Latin extended (popular Indonesian/Vietnamese/Filipino chars)
        m.put("e_acute", "é");        // é
        m.put("a_grave", "à");        // à
        m.put("n_tilde", "ñ");        // ñ
        m.put("inverted_question", "¿"); // ¿
        m.put("inverted_exclamation", "¡"); // ¡

        return Collections.unmodifiableMap(m);
    }
}
