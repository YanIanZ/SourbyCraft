from pathlib import Path
import tempfile
import unittest

import independence_policy as policy

REPO = Path(__file__).resolve().parents[1]
OWNED = "sourbycraft-server/src/main/java/dev/iyanz/sourbycraft"


class CanvasSourceCouplingTest(unittest.TestCase):
    """Canvas reached directly from SourbyCraft-owned code.

    This is the surface that would have to be rewritten if Canvas were replaced, so it
    is pinned: new coupling has to be added here deliberately, with a reason.
    """

    APPROVED = {
        # Reloading the engine's own configuration when the Sourby config is reloaded.
        # Section 5 of independence.md calls the Canvas config a compatibility surface;
        # a reload bridge is the narrowest possible form of that.
        "io.canvasmc.canvas.GlobalConfiguration.reload",
        "io.canvasmc.canvas.WorldConfig.reload",
    }

    def test_sourby_code_reaches_canvas_only_where_recorded(self):
        found = {site["symbol"] for site in policy.canvas_source_sites(REPO)}
        self.assertEqual(found, self.APPROVED,
                         "SourbyCraft code reaches a Canvas symbol that is not in the ledger; "
                         "add it to APPROVED with the reason, or route it through a "
                         "Sourby-owned integration point")

    def test_integration_patches_add_no_live_canvas_calls(self):
        self.assertEqual(policy.canvas_patch_symbols(REPO), [],
                         "a minecraft-patch adds a direct call into Canvas; engine access "
                         "belongs behind a Sourby-owned service")

    def check_source(self, body):
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            owned = root / OWNED
            owned.mkdir(parents=True)
            (owned / "Fixture.java").write_text(body)
            return policy.canvas_source_sites(root)

    def test_detects_a_new_canvas_reference(self):
        found = self.check_source("class A { void f() { io.canvasmc.canvas.Thing.go(); } }")
        self.assertEqual(len(found), 1)
        self.assertEqual(found[0]["symbol"], "io.canvasmc.canvas.Thing.go")
        self.assertEqual(found[0]["line"], 1)

    def test_ignores_a_type_merely_named_in_a_comment(self):
        # Javadoc explaining which engine class a bridge talks to is not coupling.
        for body in ("class A {\n    // see io.canvasmc.canvas.Thing\n}",
                     "class A {\n     * {@link io.canvasmc.canvas.Thing}\n}",
                     "class A {\n    /* io.canvasmc.canvas.Thing */\n}"):
            with self.subTest(body=body):
                self.assertEqual(self.check_source(body), [])

    def test_reports_every_reference_on_a_line(self):
        found = self.check_source(
            "class A { void f() { io.canvasmc.canvas.A.x(); io.canvasmc.canvas.B.y(); } }")
        self.assertEqual(len(found), 2)


class CanvasPatchCouplingTest(unittest.TestCase):
    def patch(self, *added):
        return "\n".join(["diff --git a/x.java b/x.java", "--- a/x.java", "+++ b/x.java",
                          "@@ -1,1 +1,2 @@", " context", *added]) + "\n"

    def check(self, *added):
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            target = root / policy.INTEGRATION_PATCH_ROOT / "features"
            target.mkdir(parents=True)
            (target / "0001-fixture.patch").write_text(self.patch(*added))
            return policy.canvas_patch_symbols(root)

    def test_detects_an_added_call_into_canvas(self):
        self.assertEqual(self.check("+        io.canvasmc.canvas.Engine.tick();"),
                         ["io.canvasmc.canvas.Engine.tick"])

    def test_ignores_a_removed_call(self):
        # A patch deleting a Canvas call reduces coupling; it must not count as adding it.
        self.assertEqual(self.check("-        io.canvasmc.canvas.Engine.tick();"), [])

    def test_ignores_a_comment_recording_what_was_removed(self):
        self.assertEqual(self.check("+        // (was: io.canvasmc.canvas.Engine.tick();)"), [])

    def test_ignores_the_file_header(self):
        self.assertEqual(self.check("+++ b/io/canvasmc/canvas/Thing.java"), [])

    def test_canvas_patches_are_outside_the_measured_surface(self):
        # canvas-patches/ exists only to modify Canvas and would be deleted with it.
        self.assertIn("minecraft-patches", policy.INTEGRATION_PATCH_ROOT)
        self.assertNotIn("canvas-patches", policy.INTEGRATION_PATCH_ROOT)


class ReachTest(unittest.TestCase):
    def test_the_policy_actually_reads_the_repository(self):
        seen = sum(len(list((REPO / root).rglob("*.java")))
                   for root in policy.SOURCE_ROOTS if (REPO / root).is_dir())
        self.assertGreater(seen, 20)
        self.assertTrue((REPO / policy.INTEGRATION_PATCH_ROOT).is_dir())


if __name__ == "__main__":
    unittest.main()


class SchedulerCouplingTest(unittest.TestCase):
    """The Folia internal region scheduler reached from SourbyCraft's own side.

    This is the surface an Aurora execution contract has to cover. It is pinned so the
    contract is designed against a known list rather than a moving one, and so a new
    reach into the scheduler is a deliberate act with a reason beside it.
    """

    APPROVED = {
        # Telemetry. The collector reads the global tick handle's metrics and the
        # configured tick rate; it schedules nothing and mutates nothing.
        "io.papermc.paper.threadedregions.RegionizedServer",
        "io.papermc.paper.threadedregions.TickRegionScheduler",
        "RegionizedServer",
        "TickRegionScheduler",
        # Async pathfinding hands the completion back to the owning entity's region.
        "io.papermc.paper.threadedregions.EntityScheduler",
    }

    def test_sourby_code_reaches_the_scheduler_only_where_recorded(self):
        found = {site["symbol"] for site in policy.scheduler_sites(REPO)}
        self.assertEqual(found, self.APPROVED,
                         "SourbyCraft code reaches an internal region-scheduler symbol that "
                         "is not in the ledger; add it with a reason, or route it through an "
                         "Aurora execution contract")

    def test_integration_patches_reach_the_scheduler_only_where_recorded(self):
        found = {site["symbol"] for site in policy.scheduler_patch_sites(REPO)}
        self.assertEqual(
            found,
            {"io.papermc.paper.threadedregions.TickRegionScheduler.getCurrentRegionizedWorldData"},
            "a minecraft-patch reaches into the region scheduler outside the recorded set")

    def test_the_public_folia_api_is_not_counted_as_coupling(self):
        # threadedregions.scheduler is the published Bukkit-facing API. Depending on a
        # contract is the goal, not the problem.
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            owned = root / OWNED
            owned.mkdir(parents=True)
            (owned / "Fixture.java").write_text(
                "import io.papermc.paper.threadedregions.scheduler.ScheduledTask;\n")
            self.assertEqual(policy.scheduler_sites(root), [])

    def test_a_patch_editing_the_scheduler_is_not_a_call_into_it(self):
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            patches = root / policy.INTEGRATION_PATCH_ROOT / "features"
            patches.mkdir(parents=True)
            (patches / "0001-fixture.patch").write_text(
                "+++ b/io/papermc/paper/threadedregions/TickRegionScheduler.java\n"
                "++ b/io/papermc/paper/threadedregions/TickRegions.java\n")
            self.assertEqual(policy.scheduler_patch_sites(root), [])
