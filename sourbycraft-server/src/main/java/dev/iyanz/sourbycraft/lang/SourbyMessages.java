package dev.iyanz.sourbycraft.lang;

import dev.iyanz.sourbycraft.SourbyCraftConfig;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.minimessage.MiniMessage;
import net.kyori.adventure.text.minimessage.tag.resolver.TagResolver;

import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ThreadLocalRandom;

/**
 * SourbyCraft varied, config-driven message/lang layer (F1-7).
 *
 * <p>The Folia base ships no message system: server-facing strings (server-full kick,
 * MOTD, join/leave, …) were inline {@code Component.text(...)} or vanilla defaults. This
 * class replaces that with a single, extensible layer where every message KEY maps to a
 * <b>list of variant strings</b> in the one unified operator config
 * ({@code sourbycraft_config/sourbycraft_global_config.toml}, under {@code messages.<key>}).
 * {@link #get(String, TagResolver...)} returns <b>one variant chosen at random</b> — that is the
 * "lebih bervariasi" (more varied) requirement: the same touchpoint shows a different
 * SourbyCraft-flavored, hex-colored line each time.
 *
 * <h2>Variant selection</h2>
 * Each call to {@link #get(String, TagResolver...)} picks a uniformly-random index into the variant list
 * with {@link ThreadLocalRandom} — no shared {@code Random} state, so it is safe to call
 * from any Folia region thread, the login/network thread, or the global-region scheduler
 * concurrently. The chosen raw string is parsed with {@link MiniMessage} (hex + MiniMessage
 * tags supported) into an immutable {@link Component}.
 *
 * <h2>Config schema</h2>
 * <pre>
 * [messages]
 * server-full = [ "&lt;variant 1 MiniMessage&gt;", "&lt;variant 2&gt;", ... ]
 * motd        = [ "&lt;line1&gt;\n&lt;line2&gt;", ... ]
 * join        = [ "&lt;uses &lt;player&gt; placeholder&gt;", ... ]
 * leave       = [ ... ]
 * </pre>
 * Keys are read through {@link SourbyCraftConfig#cfgGet} which already consults the unified
 * TOML first. When a key is absent/empty (or every entry is blank), {@link #get(String, TagResolver...)}
 * falls back to a sane built-in default variant registered in {@link #DEFAULTS} — the server
 * never renders an empty message and boot never depends on the operator having seeded the file.
 *
 * <h2>Placeholders</h2>
 * {@link #get(String, TagResolver...)} forwards MiniMessage {@link TagResolver}s so a caller
 * can supply e.g. {@code Placeholder.unparsed("player", name)} for the {@code join}/{@code leave}
 * messages. Variants opt in simply by writing {@code <player>} in their MiniMessage string.
 *
 * <h2>Extensibility</h2>
 * Adding a message = one {@link #DEFAULTS} entry (the built-in variants) + one seed line in
 * {@link #seedDefaults} (so it materialises in the operator file) + one {@code get(key)} call
 * at the touchpoint. No new file, no schema change.
 */
public final class SourbyMessages {

    /** Config section prefix. Every key lives under {@code messages.<key>} in the unified TOML. */
    public static final String SECTION = "messages";

    // Message keys — the first consumers (F1-7). New keys append here + to DEFAULTS + seedDefaults.
    public static final String SERVER_FULL = "server-full";
    public static final String MOTD        = "motd";
    public static final String JOIN        = "join";
    public static final String LEAVE       = "leave";

    /**
     * Built-in default variants per key (SourbyCraft-flavored, hex/MiniMessage). Used as the
     * fallback when the operator config has no (usable) list for a key, and as the seed source
     * for {@link #seedDefaults}. 2–4 variants each so even the fallback is varied.
     *
     * <p>The {@code #FFB347} / {@code #77DD77} / {@code #FF6961} / {@code #AEC6CF} / {@code #CBA6F7}
     * hexes mirror {@link dev.iyanz.sourbycraft.SourbyCraftColors} (PRIMARY/SUCCESS/WARNING/INFO/ACCENT).
     */
    private static final Map<String, List<String>> DEFAULTS = Map.of(
        SERVER_FULL, List.of(
            "<#FF6961>Waduh, servernya lagi penuh sesak!</#FF6961> <#AEC6CF>Coba mampir lagi bentar lagi ya.</#AEC6CF>",
            "<#FFB347>SourbyCraft lagi rame banget nih —</#FFB347> <gray>slot penuh, sabar sebentar sob.</gray>",
            "<#CBA6F7>Server penuh!</#CBA6F7> <#AEC6CF>Semua kursi keisi. Tunggu ada yang keluar dulu ya.</#AEC6CF>",
            "<#FF6961>Full house!</#FF6961> <gray>Server SourbyCraft lagi kepenuhan, balik lagi nanti.</gray>"
        ),
        MOTD, List.of(
            "<#FFB347><bold>SourbyCraft</bold></#FFB347> <gray>»</gray> <#AEC6CF>Ngebut, mulus, bebas lag.</#AEC6CF>\n<#77DD77>Gabung sekarang, seru-seruan bareng!</#77DD77>",
            "<#CBA6F7><bold>✦ SourbyCraft ✦</bold></#CBA6F7>\n<#AEC6CF>Powered by Canvas — tiap region jalan sendiri, TPS anti drop.</#AEC6CF>",
            "<gradient:#FFB347:#CBA6F7><bold>SourbyCraft Network</bold></gradient>\n<gray>Performa kelas atas, komunitas kelas satu.</gray>",
            "<#FFB347><bold>SourbyCraft</bold></#FFB347> <#77DD77>ONLINE</#77DD77>\n<gray>Mari main, jangan cuma ngintip MOTD doang :)</gray>"
        ),
        JOIN, List.of(
            "<#77DD77>+</#77DD77> <#FFB347><player></#FFB347> <gray>gabung ke server. Sambut yuk!</gray>",
            "<#AEC6CF>Halo,</#AEC6CF> <#FFB347><player></#FFB347> <#AEC6CF>baru aja masuk ke SourbyCraft!</#AEC6CF>",
            "<#77DD77>»</#77DD77> <#FFB347><player></#FFB347> <gray>udah nongol. Rame nih!</gray>",
            "<#CBA6F7>✦</#CBA6F7> <#FFB347><player></#FFB347> <gray>menampakkan diri. Selamat datang!</gray>"
        ),
        LEAVE, List.of(
            "<#FF6961>-</#FF6961> <#FFB347><player></#FFB347> <gray>pamit dulu. Sampai jumpa!</gray>",
            "<#AEC6CF><player></#AEC6CF> <gray>keluar dari server. Hati-hati di jalan ya.</gray>",
            "<#FF6961>«</#FF6961> <#FFB347><player></#FFB347> <gray>udah cabut. Balik lagi kapan-kapan!</gray>",
            "<gray>Yah,</gray> <#FFB347><player></#FFB347> <gray>pergi. Server jadi sepi sebentar.</gray>"
        )
    );

    /** Parsed-Component cache is intentionally absent: variants are random per-call, so caching a
     *  single Component would defeat variation. MiniMessage parse is cheap for these short strings
     *  and every touchpoint is a low-frequency event (login, join, quit, ping). */

    private SourbyMessages() {}

    /**
     * Return ONE random variant of {@code key}, parsed to a {@link Component}.
     *
     * <p>Reads {@code messages.<key>} from the unified config (a MiniMessage string list); if that
     * is absent or has no usable entry, falls back to the built-in {@link #DEFAULTS} list; if the
     * key is unknown entirely, returns an empty component (never null). Thread-safe.
     */
    public static Component get(String key, TagResolver... resolvers) {
        String raw = pickRaw(key);
        if (raw == null) return Component.empty();
        try {
            return MiniMessage.miniMessage().deserialize(raw, resolvers);
        } catch (Exception ex) {
            // Malformed operator MiniMessage — fall back to the built-in default variant, then plain text.
            String fallback = pickFromList(DEFAULTS.get(key));
            if (fallback != null && !fallback.equals(raw)) {
                try {
                    return MiniMessage.miniMessage().deserialize(fallback, resolvers);
                } catch (Exception ignored) { /* fall through to plain */ }
            }
            return Component.text(raw);
        }
    }

    /** Convenience: the raw chosen variant string (post-fallback), e.g. for a legacy String API. */
    public static String getRaw(String key) {
        String raw = pickRaw(key);
        return raw == null ? "" : raw;
    }

    /** The number of variants currently configured for a key (operator list if present+usable, else built-in). */
    public static int variantCount(String key) {
        List<String> ops = readVariants(key);
        if (ops != null && !ops.isEmpty()) return ops.size();
        List<String> def = DEFAULTS.get(key);
        return def == null ? 0 : def.size();
    }

    /**
     * Pick the raw variant: operator config list first (if it has any non-blank entry), else the
     * built-in defaults, else null for an unknown key.
     */
    private static String pickRaw(String key) {
        List<String> ops = readVariants(key);
        String chosen = pickFromList(ops);
        if (chosen != null) return chosen;
        return pickFromList(DEFAULTS.get(key));
    }

    /**
     * Read the operator variant list for a key from the unified config as {@code List<String>}.
     * Returns null when absent or not a list. Non-string / blank entries are tolerated (skipped at
     * selection time in {@link #pickFromList}).
     */
    private static List<String> readVariants(String key) {
        Object v = SourbyCraftConfig.cfgGet(SECTION + "." + key, null);
        if (!(v instanceof List<?> raw) || raw.isEmpty()) return null;
        java.util.ArrayList<String> out = new java.util.ArrayList<>(raw.size());
        for (Object o : raw) {
            if (o instanceof String s && !s.isBlank()) out.add(s);
        }
        return out.isEmpty() ? null : out;
    }

    /** Uniform-random pick of a non-blank entry from a list; null when the list is null/empty. */
    private static String pickFromList(List<String> list) {
        if (list == null || list.isEmpty()) return null;
        if (list.size() == 1) {
            String only = list.get(0);
            return (only == null || only.isBlank()) ? null : only;
        }
        // ThreadLocalRandom: no shared state -> safe across Folia region threads / login thread.
        int idx = ThreadLocalRandom.current().nextInt(list.size());
        String pick = list.get(idx);
        if (pick != null && !pick.isBlank()) return pick;
        // Fallback: linear scan for any usable entry (list had a blank at idx).
        for (String s : list) if (s != null && !s.isBlank()) return s;
        return null;
    }

    /**
     * Expose the built-in default variants for a key (immutable). Used by the config seeder in
     * {@link SourbyCraftConfig} so the operator file materialises the same varied defaults, and
     * available to tests. Returns an empty list for an unknown key.
     */
    public static List<String> defaultsFor(String key) {
        return DEFAULTS.getOrDefault(key, List.of());
    }

    /** All keys that carry built-in defaults (for seeding + diagnostics). */
    public static java.util.Set<String> keys() {
        return DEFAULTS.keySet();
    }

    /**
     * Seed the built-in varied defaults into the unified config file under {@code messages.<key>}
     * (list of MiniMessage variant strings), only when the key is absent — never clobbering an
     * operator edit. Mirrors {@code SourbyCraftConfig.seed(...)} semantics but for list values.
     *
     * <p>Called from {@code SourbyCraftConfig.seedUnifiedPerfDefaults}. The nightconfig
     * {@code CommentedFileConfig} stores lists natively; the caller saves once after all seeds.
     *
     * @param f       the nightconfig unified file (typed loosely to avoid a hard dep here)
     * @param changed a single-element boolean flag flipped true when any key was written
     */
    public static void seedDefaults(com.electronwill.nightconfig.core.file.CommentedFileConfig f, boolean[] changed) {
        if (f == null) return;
        if (f.getComment(SECTION) == null) {
            f.setComment(SECTION,
                "SourbyCraft varied server messages (F1-7). Each key is a LIST of MiniMessage/hex variants; "
                + "one is picked at random per event. Add/edit freely. Use <player> in join/leave.");
        }
        // Ordered seeding so the file reads server-full, motd, join, leave.
        for (String key : new String[]{SERVER_FULL, MOTD, JOIN, LEAVE}) {
            String path = SECTION + "." + key;
            if (!f.contains(path)) {
                f.add(path, new java.util.ArrayList<>(DEFAULTS.get(key)));
                changed[0] = true;
            }
        }
        // Per-key comments (only if not already commented).
        maybeComment(f, SECTION + "." + SERVER_FULL, "Kick line shown when a non-bypass player hits a full server (KICK_FULL).");
        maybeComment(f, SECTION + "." + MOTD, "Server-list MOTD. \\n splits two lines. One variant applied at startup.");
        maybeComment(f, SECTION + "." + JOIN, "Broadcast when a player joins. <player> = their name.");
        maybeComment(f, SECTION + "." + LEAVE, "Broadcast when a player leaves. <player> = their name.");
    }

    private static void maybeComment(com.electronwill.nightconfig.core.file.CommentedFileConfig f, String path, String comment) {
        try {
            if (f.contains(path) && f.getComment(path) == null) f.setComment(path, comment);
        } catch (Throwable ignored) { /* comment is cosmetic */ }
    }
}
