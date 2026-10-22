"""Source-shape checks only; these do not execute Java or prove runtime persistence."""
import pathlib
import unittest

ROOT = pathlib.Path(__file__).resolve().parents[1]
PATCH = ROOT / 'sourbycraft-server/minecraft-patches/features/0067-SourbyCraft-periodically-save-detached-persistent-tickets.patch'


class WorldStateAutosavePatchTest(unittest.TestCase):
    def test_hook_is_world_autosave_only(self):
        patch = PATCH.read_text()
        self.assertIn('WorldStateAutosave.save(level.getDataStorage(), sync);', patch)
        self.assertIn('note: the dragon fight is autosaved', patch)
        added = '\n'.join(line[1:] for line in patch.splitlines() if line.startswith('+') and not line.startswith('+++'))
        self.assertNotIn('Raids.TYPE', added)
        self.assertNotIn('PaperWorldPDC.TYPE', added)
        self.assertNotIn('new Thread', added)

    def test_snapshot_detaches_tickets_under_existing_section_lock(self):
        patch = PATCH.read_text()
        self.assertIn('getTicketsCopyForPersistence()', patch)
        self.assertIn('ticket.moonrise$getRemoveDelay()', patch)
        self.assertIn('this.ticketLockArea.unlock(ticketLock)', patch)
        self.assertIn('synchronized (this.deactivatedTickets)', patch)
        self.assertIn('sourbycraft$saveSnapshot', patch)
        self.assertIn('this.pendingWriteFuture', patch)


if __name__ == '__main__':
    unittest.main()
