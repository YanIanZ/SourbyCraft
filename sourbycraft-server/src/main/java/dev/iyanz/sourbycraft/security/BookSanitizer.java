package dev.iyanz.sourbycraft.security;

import net.minecraft.core.component.DataComponents;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.MutableComponent;
import net.minecraft.network.chat.Style;
import net.minecraft.server.network.Filterable;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.component.WrittenBookContent;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

/**
 * Strips click_event and hover_event style attributes from written book
 * pages on the server-bound network path. Blocks the "force op book"
 * exploit where a crafted WrittenBookContent carries
 * {@code click_event:run_command} payloads that execute with the
 * permissions of whoever opens the book.
 *
 * <p>Vanilla books built server-side via {@code Component::literal} never
 * carry these style fields; only client-sent or NBT-injected books do.
 * This sanitizer recursively walks every page component (and its
 * siblings) and rebuilds a Style with {@code clickEvent=null} and
 * {@code hoverEvent=null}.
 */
public final class BookSanitizer {

    private BookSanitizer() {}

    /**
     * Walks {@code stack} and strips dangerous events from any
     * {@code WRITTEN_BOOK_CONTENT} present. Mutates the stack in place.
     * Safe to call on stacks without a written book component.
     */
    public static void sanitize(final ItemStack stack) {
        if (stack == null || stack.isEmpty()) {
            return;
        }
        WrittenBookContent content = stack.get(DataComponents.WRITTEN_BOOK_CONTENT);
        if (content == null) {
            return;
        }
        boolean changed = false;
        List<Filterable<Component>> sanitized = new ArrayList<>(content.pages().size());
        for (Filterable<Component> page : content.pages()) {
            Component rawIn = page.raw();
            Component rawOut = stripEvents(rawIn);
            Optional<Component> filteredIn = page.filtered();
            Optional<Component> filteredOut = filteredIn.map(BookSanitizer::stripEvents);
            if (rawOut != rawIn || !filteredOut.equals(filteredIn)) {
                changed = true;
            }
            sanitized.add(new Filterable<>(rawOut, filteredOut));
        }
        if (changed) {
            stack.set(DataComponents.WRITTEN_BOOK_CONTENT, content.withReplacedPages(sanitized));
        }
    }

    private static Component stripEvents(final Component component) {
        if (component == null) {
            return null;
        }
        Style style = component.getStyle();
        boolean needsRewrite = style.getClickEvent() != null || style.getHoverEvent() != null;
        List<Component> siblings = component.getSiblings();
        List<Component> rebuiltSiblings = null;
        for (int i = 0; i < siblings.size(); i++) {
            Component child = siblings.get(i);
            Component cleanChild = stripEvents(child);
            if (cleanChild != child) {
                if (rebuiltSiblings == null) {
                    rebuiltSiblings = new ArrayList<>(siblings.size());
                    for (int j = 0; j < i; j++) {
                        rebuiltSiblings.add(siblings.get(j));
                    }
                }
                rebuiltSiblings.add(cleanChild);
            } else if (rebuiltSiblings != null) {
                rebuiltSiblings.add(child);
            }
        }
        if (!needsRewrite && rebuiltSiblings == null) {
            return component;
        }
        Style cleanStyle = needsRewrite ? style.withClickEvent(null).withHoverEvent(null) : style;
        MutableComponent rebuilt = MutableComponent.create(component.getContents()).setStyle(cleanStyle);
        List<Component> finalSiblings = rebuiltSiblings != null ? rebuiltSiblings : siblings;
        for (Component sibling : finalSiblings) {
            rebuilt.append(sibling);
        }
        return rebuilt;
    }
}
