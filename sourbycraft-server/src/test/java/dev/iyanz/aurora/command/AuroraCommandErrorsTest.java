package dev.iyanz.aurora.command;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotSame;

import com.mojang.brigadier.exceptions.CommandSyntaxException;
import org.junit.jupiter.api.Test;

/**
 * The cross-region refusal MC patch 0016 throws from {@code execute if|unless block} when the
 * executing thread does not own the position. Pinned before the class moved out of the patch into
 * Sourby source: operators see this exact text, and it must stay the Canvas-era wording
 * ({@code AbstractCommandExecution.CANNOT_DO_X_CROSS_REGION}) so there is one vocabulary.
 */
class AuroraCommandErrorsTest {

    @Test
    void theRefusalReadsCannotActionCrossRegion() {
        final CommandSyntaxException refused = AuroraCommandErrors.CANNOT_DO_X_CROSS_REGION.create("test a block");
        assertEquals("Cannot test a block cross-region", refused.getRawMessage().getString());
        assertEquals("Cannot test a block cross-region", refused.getMessage());
    }

    @Test
    void itMatchesTheEngineCommandVocabulary() {
        assertEquals(
            dev.iyanz.aurora.engine.threadedregions.commands.AbstractCommandExecution.CANNOT_DO_X_CROSS_REGION
                .create("test a block").getMessage(),
            AuroraCommandErrors.CANNOT_DO_X_CROSS_REGION.create("test a block").getMessage());
    }

    @Test
    void eachRefusalIsAFreshException() {
        assertNotSame(AuroraCommandErrors.CANNOT_DO_X_CROSS_REGION.create("x"),
            AuroraCommandErrors.CANNOT_DO_X_CROSS_REGION.create("x"));
    }
}
