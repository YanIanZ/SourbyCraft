package dev.iyanz.aurora.engine.threadedregions;

import dev.iyanz.aurora.engine.threadedregions.entities.EnderPearls;
import net.minecraft.SharedConstants;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.Identifier;
import net.minecraft.server.Bootstrap;
import net.minecraft.server.level.TicketType;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;

/**
 * Engine identifiers renamed from the {@code canvas} namespace to {@code aurora} (owner decision 2026-10-21). The
 * two ticket types carry no persist flag, so nothing stored under the old id needs reading; the pearl saved data
 * does and is moved by {@code PearlsMigration}.
 */
class AuroraIdentifiersTest {

    @BeforeAll
    static void bootstrap() {
        SharedConstants.tryDetectVersion();
        Bootstrap.bootStrap();
    }

    @Test
    void engineTicketTypesUseTheAuroraNamespaceAndAreNotPersisted() {
        assertEquals(Identifier.parse("aurora:end_credits"), BuiltInRegistries.TICKET_TYPE.getKey(TicketType.END_CREDITS));
        assertEquals(Identifier.parse("aurora:region_profiler_hold"), BuiltInRegistries.TICKET_TYPE.getKey(TicketType.REGION_PROFILING_HOLD));
        assertFalse(TicketType.END_CREDITS.persist(), "a renamed id is only safe because it is never saved");
        assertFalse(TicketType.REGION_PROFILING_HOLD.persist());
        assertFalse(BuiltInRegistries.TICKET_TYPE.containsKey(Identifier.parse("canvas:end_credits")));
    }

    @Test
    void pearlSavedDataIsAuroraPearls() {
        assertEquals(Identifier.parse("aurora:pearls"), EnderPearls.TYPE.id());
    }
}
