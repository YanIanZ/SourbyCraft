package dev.iyanz.sourbycraft.commandblock;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.iyanz.sourbycraft.commandblock.CommandBlockPolicy.Decision;
import java.util.ArrayList;
import java.util.List;
import java.util.function.Predicate;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

class CommandBlockPolicyTest {

    @AfterEach
    void restore() {
        CommandBlockPolicy.setEnabled(false);
    }

    @Test
    void offByDefaultAndOffKeepsTheInheritedRefusal() {
        assertFalse(CommandBlockPolicy.enabled());
        assertEquals(Decision.DISABLED, CommandBlockPolicy.decide(false, true));
        assertEquals(Decision.DISABLED, CommandBlockPolicy.decide(false, false));
        assertEquals(Decision.DISABLED, CommandBlockPolicy.decide(true));
    }

    @Test
    void onRunsOnlyOnTheOwningRegion() {
        assertEquals(Decision.RUN, CommandBlockPolicy.decide(true, true));
        assertEquals(Decision.NOT_OWNER, CommandBlockPolicy.decide(true, false));
        CommandBlockPolicy.setEnabled(true);
        assertEquals(Decision.RUN, CommandBlockPolicy.decide(true));
        assertEquals(Decision.NOT_OWNER, CommandBlockPolicy.decide(false));
    }

    @Test
    void scopeIsThreadLocalAndNests() throws Exception {
        assertFalse(CommandBlockPolicy.inScope());
        try (CommandBlockPolicy.Scope outer = CommandBlockPolicy.enter()) {
            assertTrue(CommandBlockPolicy.inScope());
            try (CommandBlockPolicy.Scope inner = CommandBlockPolicy.enter()) {
                assertTrue(CommandBlockPolicy.inScope());
            }
            assertTrue(CommandBlockPolicy.inScope(), "closing the inner scope must not end the outer one");
            final boolean[] other = new boolean[1];
            final Thread thread = new Thread(() -> other[0] = CommandBlockPolicy.inScope());
            thread.start();
            thread.join();
            assertFalse(other[0], "another thread never sees this thread's scope");
        }
        assertFalse(CommandBlockPolicy.inScope());
    }

    @Test
    void scopeCloseIsIdempotent() {
        final CommandBlockPolicy.Scope scope = CommandBlockPolicy.enter();
        try (CommandBlockPolicy.Scope inner = CommandBlockPolicy.enter()) {
            inner.close();
        }
        assertTrue(CommandBlockPolicy.inScope());
        scope.close();
        scope.close();
        assertFalse(CommandBlockPolicy.inScope());
    }

    @Test
    void selectorsOutsideAScopeAreUnchanged() {
        final Predicate<String> selector = s -> true;
        assertSame(selector, CommandBlockPolicy.ownedFirst(false, s -> false, selector));
    }

    @Test
    void selectorsInAScopeCheckOwnershipBeforeReadingTheEntity() {
        final List<String> read = new ArrayList<>();
        final Predicate<String> selector = s -> {
            read.add(s);
            return true;
        };
        final Predicate<String> owned = CommandBlockPolicy.ownedFirst(true, s -> s.startsWith("own"), selector);
        assertTrue(owned.test("own-zombie"));
        assertFalse(owned.test("far-player"));
        assertEquals(List.of("own-zombie"), read, "a foreign entity's state must not be read");
    }

    @Test
    void selectorStillAppliesItsOwnFilterInAScope() {
        final Predicate<String> owned = CommandBlockPolicy.ownedFirst(true, s -> true, s -> s.endsWith("tagged"));
        assertTrue(owned.test("tagged"));
        assertFalse(owned.test("plain"));
    }

    @Test
    void nameAndUuidLookupsAreFilteredInAScope() {
        final Predicate<String> owner = s -> s.startsWith("own");
        assertEquals("own", CommandBlockPolicy.ownedOrNull(true, "own", owner));
        assertNull(CommandBlockPolicy.ownedOrNull(true, "far", owner));
        assertEquals("far", CommandBlockPolicy.ownedOrNull(false, "far", owner));
        assertNull(CommandBlockPolicy.ownedOrNull(true, null, owner));
    }

    @Test
    void blockTargetsOutsideTheRegionAreRefusedOnlyInAScope() {
        assertTrue(CommandBlockPolicy.allowsTarget(false, false), "players and console keep the engine's routing");
        assertTrue(CommandBlockPolicy.allowsTarget(true, true));
        assertFalse(CommandBlockPolicy.allowsTarget(true, false));
    }

    @Test
    void refusalMessageNamesTheAction() {
        assertEquals("Command block: cannot place blocks outside the region that owns the command block",
            CommandBlockPolicy.crossRegionMessage("place blocks"));
    }

    @Test
    void boundingBoxReadsNeedOwnershipInAScope() {
        assertTrue(CommandBlockPolicy.mayRead(false, false), "players and console unchanged");
        assertTrue(CommandBlockPolicy.mayRead(true, true));
        assertFalse(CommandBlockPolicy.mayRead(true, false));
    }

    @Test
    void aForeignPredecessorReadsAsConditionNotMetOnlyWhenOn() {
        assertTrue(CommandBlockPolicy.mayReadPredecessor(false, false), "off keeps the inherited read");
        assertTrue(CommandBlockPolicy.mayReadPredecessor(true, true));
        assertFalse(CommandBlockPolicy.mayReadPredecessor(true, false));
    }

    @Test
    void commandBlocksNeverDeferAMutation() {
        assertEquals(CommandBlockPolicy.Deferred.ROUTE_ASYNC, CommandBlockPolicy.deferred(false, false, false));
        assertEquals(CommandBlockPolicy.Deferred.ROUTE_ASYNC, CommandBlockPolicy.deferred(false, true, true));
        assertEquals(CommandBlockPolicy.Deferred.REFUSE_FOREIGN, CommandBlockPolicy.deferred(true, false, true));
        assertEquals(CommandBlockPolicy.Deferred.REFUSE_FOREIGN, CommandBlockPolicy.deferred(true, false, false));
        assertEquals(CommandBlockPolicy.Deferred.REFUSE_UNLOADED, CommandBlockPolicy.deferred(true, true, false));
        assertEquals(CommandBlockPolicy.Deferred.RUN_NOW, CommandBlockPolicy.deferred(true, true, true));
    }

    @Test
    void lateOrForeignOutputGoesToDiagnosticsOnlyWhenOn() {
        assertFalse(CommandBlockPolicy.toDiagnostics(false, true, false), "off: inherited behaviour");
        assertFalse(CommandBlockPolicy.toDiagnostics(true, false, true), "open, owner: last output as vanilla");
        assertTrue(CommandBlockPolicy.toDiagnostics(true, true, true), "closed: deferred");
        assertTrue(CommandBlockPolicy.toDiagnostics(true, false, false), "foreign thread");
    }
}
