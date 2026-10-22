package dev.iyanz.sourbycraft.command;

import dev.iyanz.sourbycraft.SourbyCraftColors;
import dev.iyanz.sourbycraft.brand.ConsoleStyle;
import java.util.List;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.event.ClickEvent;
import net.kyori.adventure.text.event.HoverEvent;
import net.kyori.adventure.text.format.TextDecoration;
import static net.kyori.adventure.text.Component.text;

/** Shared visual hierarchy. Plain text remains useful without click/hover support. */
public final class UiPanel {
    public static final String RULE = "────────────────────────────";
    private UiPanel() {}

    public static Component header(String title) {
        return text("╭─ ", SourbyCraftColors.DIM)
            .append(text("SourbyCraft", SourbyCraftColors.HEADER).decorate(TextDecoration.BOLD))
            .append(text(" / ", SourbyCraftColors.DIM))
            .append(text(ConsoleStyle.singleLine(title), SourbyCraftColors.VALUE));
    }

    public static Component section(String label) {
        return text("  " + ConsoleStyle.singleLine(label), SourbyCraftColors.ACCENT).decorate(TextDecoration.BOLD);
    }

    public static Component row(String label, String value) {
        return text("  " + label + ": ", SourbyCraftColors.LABEL)
            .append(text(ConsoleStyle.singleLine(value), SourbyCraftColors.VALUE));
    }

    public static Component hint(String value) {
        return text("  " + ConsoleStyle.singleLine(value), SourbyCraftColors.DIM);
    }

    public static Component footer() { return text("╰─ " + RULE, SourbyCraftColors.DIM); }

    public static Component action(String label, String command, String explanation) {
        return text("[" + label + "]", SourbyCraftColors.PRIMARY)
            .clickEvent(ClickEvent.suggestCommand(command))
            .hoverEvent(HoverEvent.showText(text(explanation + "\n" + command, SourbyCraftColors.VALUE)));
    }

    public static Component actions(List<String> commands) {
        Component line = text("  ");
        for (String command : commands) {
            line = line.append(action(command, command, "Open this view")).append(text("  ", SourbyCraftColors.DIM));
        }
        return line;
    }
}
