package dev.iyanz.sourbycraft.awf.multiprofile;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.iyanz.sourbycraft.api.profile.CoopPage;
import dev.iyanz.sourbycraft.api.profile.ProfileAuditEntry;
import dev.iyanz.sourbycraft.api.profile.ProfileErrorCode;
import dev.iyanz.sourbycraft.api.profile.ProfileOperationException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.RejectedExecutionException;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * Build 55: the audit log records after the fact and never throws; the ring pages newest first per profile (or actor);
 * the daily file gets one sanitised line per entry on the lane; a closed lane counts drops instead of failing anything.
 */
class ProfileAuditTest {

    @TempDir
    Path store;

    @Test
    void recordsToTheRingAndTheDailyFile() throws Exception {
        final ProfileAudit audit = new ProfileAudit(this.store, 30, Runnable::run);
        final UUID profile = UUID.randomUUID();
        final UUID actor = UUID.randomUUID();
        final UUID op = UUID.randomUUID();
        final ProfileAuditEntry first = audit.record("switch.committed", profile, actor, op, true, null, "from null in 12 ms");
        final ProfileAuditEntry second = audit.record("switch.refused", profile, actor, null, false, ProfileErrorCode.SWITCH_COOLDOWN,
            "wait\t5 s\nmore");
        assertNotNull(first);
        assertEquals(1L, first.sequence());
        assertEquals(2L, second.sequence());
        assertEquals("wait 5 s more", second.detail(), "tabs and line breaks never break the one-line format");
        assertEquals(2L, audit.recorded());
        assertEquals(0L, audit.dropped());
        assertEquals(0L, audit.writeFailures());
        assertEquals(this.store.resolve("audit"), audit.directory());
        try (Stream<Path> files = Files.list(audit.directory())) {
            final List<Path> segments = files.toList();
            assertEquals(1, segments.size());
            final List<String> lines = Files.readAllLines(segments.get(0), StandardCharsets.UTF_8);
            assertEquals(2, lines.size());
            final String[] columns = lines.get(0).split("\t");
            assertEquals(8, columns.length, lines.get(0));
            assertEquals("1", columns[0]);
            assertEquals("switch.committed", columns[2]);
            assertEquals(profile.toString(), columns[3]);
            assertEquals(actor.toString(), columns[4]);
            assertEquals(op.toString(), columns[5]);
            assertEquals("OK", columns[6]);
            assertTrue(lines.get(1).contains("\tSWITCH_COOLDOWN\t"), lines.get(1));
        }
        // Newest first; the actor's own page finds the same entries; another profile sees nothing.
        final CoopPage<ProfileAuditEntry> page = audit.page(profile, null);
        assertEquals(List.of(2L, 1L), page.items().stream().map(ProfileAuditEntry::sequence).toList());
        assertNull(page.nextCursor());
        assertEquals(2, audit.page(actor, null).items().size());
        assertTrue(audit.page(UUID.randomUUID(), null).items().isEmpty());
        assertThrows(ProfileOperationException.class, () -> audit.page(profile, "nonsense"));
    }

    @Test
    void pagesWithACursorAndBoundsTheRing() {
        final ProfileAudit audit = new ProfileAudit(this.store, 1, Runnable::run);
        final UUID profile = UUID.randomUUID();
        for (int i = 0; i < CoopPage.MAX_PAGE_SIZE + 5; i++) {
            audit.record("profile.locked", profile, null, null, true, null, "entry " + i);
        }
        final CoopPage<ProfileAuditEntry> first = audit.page(profile, null);
        assertEquals(CoopPage.MAX_PAGE_SIZE, first.items().size());
        assertNotNull(first.nextCursor());
        assertEquals(CoopPage.MAX_PAGE_SIZE + 5L, first.items().get(0).sequence());
        final CoopPage<ProfileAuditEntry> second = audit.page(profile, first.nextCursor());
        assertEquals(5, second.items().size());
        assertNull(second.nextCursor());
        assertEquals(1L, second.items().get(4).sequence());
        // More entries than the ring: the oldest fall out; nothing is thrown and nothing else is counted as dropped.
        for (int i = 0; i < ProfileAudit.MEMORY_ENTRIES; i++) audit.record("bank.deposit", profile, null, null, true, null, "");
        final CoopPage<ProfileAuditEntry> newest = audit.page(profile, null);
        assertEquals("bank.deposit", newest.items().get(0).kind());
        assertEquals(0L, audit.dropped());
        assertEquals(ProfileAudit.MEMORY_ENTRIES + CoopPage.MAX_PAGE_SIZE + 5L, audit.recorded());
    }

    @Test
    void aRefusingLaneCountsDropsAndNeverThrows() {
        final ProfileAudit audit = new ProfileAudit(this.store, 30, task -> {
            throw new RejectedExecutionException("lane closed");
        });
        final UUID profile = UUID.randomUUID();
        assertNotNull(audit.record("profile.created", profile, profile, null, true, null, "type classic"));
        assertEquals(1L, audit.recorded(), "the ring took it");
        assertEquals(1L, audit.dropped(), "the file write was dropped and counted");
        assertEquals(1, audit.page(profile, null).items().size());
        audit.close();
        audit.record("profile.deleted", profile, profile, null, true, null, "");
        assertEquals(2L, audit.dropped(), "after close every entry is a counted drop");
        assertFalse(Files.exists(audit.directory()), "nothing reached the disk");
    }

    /** Review P3-11: {@code close()} refuses new entries but never drops a write already handed to the lane. */
    @Test
    void aWriteHandedToTheLaneBeforeCloseStillLands() throws Exception {
        final java.util.ArrayDeque<Runnable> handed = new java.util.ArrayDeque<>();
        final ProfileAudit audit = new ProfileAudit(this.store, 30, handed::add);
        final UUID profile = UUID.randomUUID();
        assertNotNull(audit.record("profile.archived", profile, null, null, true, null, "archived read-only"));
        assertEquals(1, handed.size(), "the write was not handed to the lane");
        audit.close();
        audit.record("profile.unarchived", profile, null, null, true, null, "");
        assertEquals(1L, audit.dropped(), "the entry recorded after close is the one drop");
        handed.poll().run();
        assertEquals(0L, audit.writeFailures());
        assertEquals(1L, audit.dropped(), "the queued write was dropped by the close");
        try (Stream<Path> files = Files.list(audit.directory())) {
            final List<String> lines = Files.readAllLines(files.toList().get(0), StandardCharsets.UTF_8);
            assertEquals(1, lines.size());
            assertTrue(lines.get(0).contains("profile.archived"), lines.get(0));
        }
    }

    @Test
    void anUnwritableDirectoryIsCountedNotThrown() throws Exception {
        final Path file = this.store.resolve("audit");
        Files.writeString(file, "a file where the directory should be");
        final ProfileAudit audit = new ProfileAudit(this.store, 30, Runnable::run);
        audit.record("profile.created", UUID.randomUUID(), null, null, true, null, "");
        assertEquals(1L, audit.recorded());
        assertEquals(1L, audit.writeFailures());
        assertEquals(0L, audit.dropped());
    }

    @Test
    void entryContract() {
        assertThrows(IllegalArgumentException.class, () -> new ProfileAuditEntry(1L, 0L, "", null, null, null, true, null, ""));
        assertThrows(IllegalArgumentException.class, () -> new ProfileAuditEntry(1L, 0L, "x", null, null, null, true,
            ProfileErrorCode.INVALID_REQUEST, ""));
        final ProfileAuditEntry cut = new ProfileAuditEntry(1L, 0L, "x", null, null, null, true, null, "a".repeat(ProfileAuditEntry.MAX_DETAIL + 10));
        assertEquals(ProfileAuditEntry.MAX_DETAIL, cut.detail().length());
        assertEquals("a b", ProfileAudit.sanitize("a\u0000b"));
        assertTrue(ProfileAudit.line(cut).endsWith("\n"));
    }
}
