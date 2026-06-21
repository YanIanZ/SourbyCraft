package dev.iyanz.sourbycraft.chat;

import dev.iyanz.sourbycraft.util.TextRender;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.TextReplacementConfig;
import net.kyori.adventure.text.serializer.plain.PlainTextComponentSerializer;
import org.bukkit.block.Block;
import org.bukkit.block.BlockState;
import org.bukkit.block.Sign;
import org.bukkit.block.sign.Side;
import org.bukkit.block.sign.SignSide;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.block.SignChangeEvent;
import org.bukkit.event.player.PlayerInteractEvent;
import org.bukkit.event.world.WorldLoadEvent;
import org.bukkit.event.Event;
import org.bukkit.plugin.Plugin;
import org.bukkit.Bukkit;

import java.util.regex.Pattern;

/**
 * Strips Unicode variation selectors + zero-width joiners from sign text so
 * vanilla MC font doesn't render them as `VS16` missing-glyph boxes.
 *
 * <p>Handles two paths:
 * <ul>
 *   <li>{@link SignChangeEvent} — strips at edit time so newly placed signs
 *       never persist the offending codepoints.</li>
 *   <li>{@link PlayerInteractEvent} on right-click sign — opportunistically
 *       re-sanitises an already-saved sign (player must right-click once after
 *       the r39+ update; no automatic world-scan migration to keep boot fast).</li>
 * </ul>
 */
public final class SignSanitizer implements Listener {

    private static final Pattern COMBINERS = Pattern.compile("[\\uFE00-\\uFE0F\\u200D]|[\\uDB40\\uDD00-\\uDB40\\uDDFF]");

    private SignSanitizer() {}

    public static void register(Plugin plugin) {
        Bukkit.getPluginManager().registerEvents(new SignSanitizer(), plugin);
    }

    private static Component strip(Component in) {
        if (in == null) return null;
        // Fast-path: no combiners present → return same instance.
        String plain = PlainTextComponentSerializer.plainText().serialize(in);
        if (!COMBINERS.matcher(plain).find()) return in;
        TextReplacementConfig cfg = TextReplacementConfig.builder()
                .match(COMBINERS)
                .replacement((match, b) -> Component.empty())
                .build();
        return in.replaceText(cfg);
    }

    @EventHandler(priority = EventPriority.LOWEST, ignoreCancelled = true)
    public void onSignChange(SignChangeEvent e) {
        java.util.List<Component> lines = e.lines();
        boolean changed = false;
        for (int i = 0; i < lines.size(); i++) {
            Component before = lines.get(i);
            Component after = strip(before);
            if (after != before) {
                e.line(i, after);
                changed = true;
            }
        }
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onSignInteract(PlayerInteractEvent e) {
        if (e.getAction() != org.bukkit.event.block.Action.RIGHT_CLICK_BLOCK) return;
        Block b = e.getClickedBlock();
        if (b == null) return;
        BlockState st = b.getState(false);
        if (!(st instanceof Sign sign)) return;
        boolean any = false;
        for (Side side : Side.values()) {
            SignSide ss = sign.getSide(side);
            java.util.List<Component> lines = ss.lines();
            for (int i = 0; i < lines.size(); i++) {
                Component before = lines.get(i);
                Component after = strip(before);
                if (after != before) {
                    ss.line(i, after);
                    any = true;
                }
            }
        }
        if (any) sign.update(false, false);
    }
}
