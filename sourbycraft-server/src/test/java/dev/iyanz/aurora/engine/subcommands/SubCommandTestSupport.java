package dev.iyanz.aurora.engine.subcommands;

import com.mojang.brigadier.CommandDispatcher;
import com.mojang.brigadier.ParseResults;
import com.mojang.brigadier.builder.LiteralArgumentBuilder;
import com.mojang.brigadier.exceptions.CommandSyntaxException;
import com.mojang.brigadier.suggestion.Suggestion;
import dev.iyanz.aurora.engine.commands.CanvasCommands;
import dev.iyanz.aurora.engine.commands.SubCommand;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.TimeUnit;
import java.util.function.Supplier;
import net.minecraft.commands.CommandBuildContext;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.server.permissions.Permission;
import net.minecraft.server.permissions.Permissions;
import net.minecraft.network.chat.Component;
import org.junit.jupiter.api.Assertions;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * Shared pieces for the {@code subcommands/} tests: a Brigadier dispatcher holding one sub command
 * exactly as {@code CanvasCommands.build} would hand it over (a literal carrying the
 * {@code canvas.command.<name>} requirement), a mocked {@link CommandSourceStack} that records what
 * it was told, and parse-only helpers.
 *
 * <p>Nothing here starts a server. {@code CanvasCommands.build} is not used because the {@code /canvas}
 * tree is no longer registered (feature patch 0020) and its sub command list is process-wide static
 * state a test must not touch.</p>
 */
final class SubCommandTestSupport {

    private SubCommandTestSupport() {}

    /** A source that records permission questions and every {@code sendSuccess}/{@code sendSystemMessage} text. */
    static final class RecordingSource {
        final CommandSourceStack stack = mock(CommandSourceStack.class);
        final List<String> asked = new ArrayList<>(); // nodes asked at the admin level
        final List<String> messages = new ArrayList<>();
        private boolean permitted;

        RecordingSource(final boolean permitted) {
            this.permitted = permitted;
            when(this.stack.hasPermission(any(Permission.class), anyString())).thenAnswer(call -> {
                if (call.getArgument(0) != Permissions.COMMANDS_ADMIN) {
                    return true; // vanilla argument types ask about entity selectors and the like; not what is under test
                }
                this.asked.add(call.getArgument(1));
                return this.permitted;
            });
            doAnswer(call -> {
                final Supplier<Component> supplier = call.getArgument(0);
                this.messages.add(supplier.get().getString());
                return null;
            }).when(this.stack).sendSuccess(any(), anyBoolean());
            doAnswer(call -> {
                this.messages.add(((Component) call.getArgument(0)).getString());
                return null;
            }).when(this.stack).sendSystemMessage(any(Component.class));
        }

        void permit(final boolean permitted) {
            this.permitted = permitted;
        }
    }

    /** What the registrar does with a sub command: its own literal, guarded by the canvas permission node. */
    static CommandDispatcher<CommandSourceStack> dispatcher(final SubCommand sub) {
        final CommandDispatcher<CommandSourceStack> dispatcher = new CommandDispatcher<>();
        dispatcher.register(sub.construct(
            LiteralArgumentBuilder.<CommandSourceStack>literal(sub.getName()).requires(CanvasCommands.permission(sub.getName())),
            mock(CommandBuildContext.class)
        ));
        return dispatcher;
    }

    /** True when {@code input} parses completely and ends on a node that can be executed. */
    static boolean executable(final CommandDispatcher<CommandSourceStack> dispatcher, final String input, final CommandSourceStack source) {
        final ParseResults<CommandSourceStack> parsed = dispatcher.parse(input, source);
        return !parsed.getReader().canRead()
            && parsed.getExceptions().isEmpty()
            && parsed.getContext().getLastChild().getCommand() != null;
    }

    /** The suggestions at the end of {@code input}. */
    static List<String> suggestions(final CommandDispatcher<CommandSourceStack> dispatcher, final String input, final CommandSourceStack source) throws Exception {
        return dispatcher.getCompletionSuggestions(dispatcher.parse(input, source))
            .get(20, TimeUnit.SECONDS).getList().stream().map(Suggestion::getText).toList();
    }

    /** Executes and returns the thrown syntax error's text, failing if it does not throw one. */
    static String syntaxError(final CommandDispatcher<CommandSourceStack> dispatcher, final String input, final CommandSourceStack source) {
        try {
            dispatcher.execute(input, source);
        } catch (final CommandSyntaxException expected) {
            return expected.getMessage();
        }
        throw new AssertionError("expected a syntax error from: " + input);
    }

    /** Asserts the registrar-provided requirement is {@code canvas.command.<name>} at the admin level. */
    static void assertGuardedByCanvasNode(final SubCommand sub, final String node) {
        final CommandDispatcher<CommandSourceStack> dispatcher = dispatcher(sub);
        final RecordingSource denied = new RecordingSource(false);
        final RecordingSource granted = new RecordingSource(true);

        Assertions.assertFalse(dispatcher.getRoot().getChild(sub.getName()).canUse(denied.stack), sub.getName() + " must not run for a sender without the node");
        Assertions.assertTrue(dispatcher.getRoot().getChild(sub.getName()).canUse(granted.stack));
        Assertions.assertEquals(List.of(node), denied.asked, "exactly the expected node is checked");
        Assertions.assertEquals(List.of(node), granted.asked);
    }
}
