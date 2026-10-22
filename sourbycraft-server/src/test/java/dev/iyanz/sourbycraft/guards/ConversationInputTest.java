package dev.iyanz.sourbycraft.guards;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;

class ConversationInputTest {

    private final List<Runnable> playerRegion = new ArrayList<>();
    private final List<String> accepted = new ArrayList<>();
    private final List<String> errors = new ArrayList<>();

    @Test
    void theInputIsDeliveredOnlyWhereThePlayerRegionRunsIt() {
        ConversationInput.route("alice", "yes", this.playerRegion::add, this.accepted::add, this.errors::add);
        assertTrue(this.accepted.isEmpty(), "not on the calling (chat) thread");
        assertEquals(1, this.playerRegion.size());
        this.playerRegion.get(0).run();
        assertEquals(List.of("yes"), this.accepted);
        assertTrue(this.errors.isEmpty());
    }

    @Test
    void aFailingPromptIsReportedAgainstThePlayerAndDoesNotEscape() {
        ConversationInput.route("alice", "boom", this.playerRegion::add, input -> {
            throw new IllegalStateException("prompt broke");
        }, this.errors::add);
        this.playerRegion.get(0).run();
        assertEquals(1, this.errors.size());
        assertTrue(this.errors.get(0).contains("alice") && this.errors.get(0).contains("prompt broke"), this.errors.get(0));
    }
}
