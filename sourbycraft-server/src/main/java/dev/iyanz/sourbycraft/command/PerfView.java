package dev.iyanz.sourbycraft.command;

import dev.iyanz.sourbycraft.api.metrics.PerformanceSnapshot;
import java.util.List;
import java.util.function.Consumer;
import net.kyori.adventure.text.Component;
import org.bukkit.command.CommandSender;

/**
 * One {@code /perf} view. {@link PerfCommand} owns the frame (header, freshness, actions,
 * footer) and the argument dispatch; a view supplies its name, who sees it, its body lines and
 * the completion of the arguments after its name. Views are registered in {@link PerfViews}.
 *
 * <p>Read-only: a body is rendered from the one snapshot the frame took plus whatever published
 * counters the view reads; it never queries a world or the OS.</p>
 */
interface PerfView {

    /** The argument that selects the view; lower case. */
    String name();

    /** Whether the view is named in the usage text and offered by tab completion. */
    default boolean listed() {
        return true;
    }

    /**
     * Whether {@code sender} may see the view at all, on top of the command's own permission. A
     * view that is not visible behaves as if it did not exist: not completed, and selecting it
     * shows the usage text.
     */
    default boolean visibleTo(final CommandSender sender) {
        return true;
    }

    /** The header title. */
    default String title() {
        return "Performance / " + name();
    }

    /** Appends the body lines, between the header and the frame's trailer. */
    void render(PerformanceSnapshot snapshot, List<Component> lines);

    /**
     * Handles an argument form of its own ({@code args[0]} is the view name) and replies to
     * {@code sender}; returns false to fall through to the common "one argument, render" path.
     */
    default boolean execute(final CommandSender sender, final String command, final String[] args) {
        return false;
    }

    /** Completion candidates when {@code args.length >= 2}; {@code args[0]} is the view name. */
    default List<String> complete(final String[] args) {
        return List.of();
    }

    /** A view whose body needs no snapshot, such as the existing row classes. */
    static PerfView of(final String name, final Consumer<List<Component>> body) {
        return new PerfView() {
            @Override public String name() { return name; }
            @Override public void render(final PerformanceSnapshot snapshot, final List<Component> lines) { body.accept(lines); }
        };
    }
}
