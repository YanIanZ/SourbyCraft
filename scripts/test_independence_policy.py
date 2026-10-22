from pathlib import Path
import re
import tempfile
import unittest

import independence_policy as policy

REPO = Path(__file__).resolve().parents[1]
OWNED = "sourbycraft-server/src/main/java/dev/iyanz/sourbycraft"
PAPER_FEATURES = "sourbycraft-server/paper-patches/features"

ALL_ROOTS = policy.SOURCE_ROOTS + (policy.ENGINE_ROOT,) + policy.INTEGRATION_PATCH_ROOTS


def layout(root):
    """A fixture tree with every policy root present, since a missing root is an error."""
    for relative in ALL_ROOTS:
        (root / relative).mkdir(parents=True, exist_ok=True)


class CanvasSourceCouplingTest(unittest.TestCase):
    """Canvas reached directly from SourbyCraft-owned code.

    This is the surface that would have to be rewritten if Canvas were replaced, so it
    is pinned: new coupling has to be added here deliberately, with a reason.
    """

    APPROVED = {
        # Reloading the engine's own configuration when the Sourby config is reloaded.
        # Section 5 of independence.md calls the Canvas config a compatibility surface;
        # a reload bridge is the narrowest possible form of that.
        "dev.iyanz.aurora.engine.GlobalConfiguration.reload",
        "dev.iyanz.aurora.engine.WorldConfig.reload",
        # Runtime AWF world lifecycle (dependency-ledger.md section 1.2): a durable save waits for
        # each region's save ticket. (The unload outcome and the unload event are SourbyCraft's
        # own plugin API since Build 48, dev.iyanz.sourbycraft.api, so they are not engine coupling.)
        "dev.iyanz.aurora.engine.util.ticket.SaveAllTicket",
        # /perf scheduler (dependency-ledger.md section 1.2, recorded 2026-10-20): the configured
        # scheduler kind and thread count, and the READY/TRANSIENT region split. Read-only;
        # declared here rather than reached around by matching class names as strings. The region
        # state it reads is SourbyCraft's plugin API type (dev.iyanz.sourbycraft.api.region).
        "dev.iyanz.aurora.engine.threadedregions.SchedulerUtil",
    }

    ADAPTER = ("sourbycraft-server/src/main/java/dev/iyanz/sourbycraft/config/upstream/"
               "CanvasConfigBridge.java")
    WORLDS = "sourbycraft-server/src/main/java/dev/iyanz/sourbycraft/awf/world/AuroraWorldsService.java"
    SCHEDULER_VIEW = "sourbycraft-server/src/main/java/dev/iyanz/sourbycraft/perf/LiveSchedulerSource.java"

    def test_canvas_is_named_only_inside_its_bridge(self):
        # Same rule as the region backend: replacing the implementation should be an edit to a
        # known file, not a search. The reload path above it names no engine at all.
        files = {site["file"] for site in policy.canvas_source_sites(REPO)}
        self.assertEqual(files, {self.ADAPTER, self.WORLDS, self.SCHEDULER_VIEW},
                         "Canvas is named outside its bridge; route it through UpstreamConfigBridge")

    def test_sourby_code_reaches_canvas_only_where_recorded(self):
        found = {site["symbol"] for site in policy.canvas_source_sites(REPO)}
        self.assertEqual(found, self.APPROVED,
                         "SourbyCraft code reaches a Canvas symbol that is not in the ledger; "
                         "add it to APPROVED with the reason, or route it through a "
                         "Sourby-owned integration point")

    # Feature patches that name a Canvas symbol, with the reason. Empty since Build 48: the
    # io.canvasmc plugin API was removed (owner decision 2026-10-21) and Paper feature 0014 names
    # dev.iyanz.sourbycraft.api.world.WorldUnloadResult by its simple name.
    PATCH_APPROVED = []

    def test_integration_patches_add_no_live_canvas_calls(self):
        self.assertEqual(sorted(policy.canvas_patch_symbols(REPO)), sorted(self.PATCH_APPROVED),
                         "a feature patch adds a direct call into Canvas; engine access "
                         "belongs behind a Sourby-owned service, or the symbol is recorded "
                         "in PATCH_APPROVED with its reason")

    def check_source(self, body):
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            layout(root)
            (root / OWNED / "Fixture.java").write_text(body)
            return policy.canvas_source_sites(root)

    def test_detects_a_renamed_engine_reference(self):
        found = self.check_source("class A { void f() { dev.iyanz.aurora.engine.Thing.go(); } }")
        self.assertEqual([site["symbol"] for site in found], ["dev.iyanz.aurora.engine.Thing.go"])

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

    def test_the_engine_package_is_not_scanned_as_sourby_code(self):
        # The engine naming its own classes is not coupling; it is only required to exist.
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            layout(root)
            (root / policy.ENGINE_ROOT / "Engine.java").write_text(
                "class Engine { void f() { dev.iyanz.aurora.engine.Thing.go(); } }")
            self.assertEqual(policy.canvas_source_sites(root), [])
            self.assertEqual(policy.scheduler_sites(root), [])


class CanvasPackageRemovedTest(unittest.TestCase):
    """Build 48 removed the io.canvasmc plugin API outright (owner decision 2026-10-21, no shim).

    The package must not come back anywhere SourbyCraft ships code from: the API sources, the
    server sources, and every patch tree, the engine baseline included.
    """

    ROOTS = (
        "sourbyapi/src",
        "sourbycraft-server/src/main",
        "sourbycraft-server/src/log4jPlugins",
        "sourbycraft-server/minecraft-patches",
        "sourbycraft-server/paper-patches",
        "sourbyapi/paper-patches",
    )
    FORBIDDEN = re.compile(r"io\.canvasmc")

    def test_no_io_canvasmc_reference_remains(self):
        offenders = []
        for relative in self.ROOTS:
            base = REPO / relative
            self.assertTrue(base.is_dir(), relative)
            for path in sorted(p for p in base.rglob("*") if p.is_file()):
                text = path.read_text(errors="replace")
                for number, line in enumerate(text.splitlines(), 1):
                    if self.FORBIDDEN.search(line):
                        offenders.append(f"{path.relative_to(REPO)}:{number}: {line.strip()}")
        self.assertEqual(offenders, [], "io.canvasmc was removed in Build 48; use dev.iyanz.sourbycraft.api")

    def test_the_package_directories_are_gone(self):
        for relative in ("sourbyapi/src/main/java/io/canvasmc", "sourbyapi/src/test/java/io/canvasmc"):
            self.assertFalse((REPO / relative).exists(), relative)

    def test_the_owned_api_packages_exist(self):
        api = REPO / "sourbyapi/src/main/java/dev/iyanz/sourbycraft/api"
        for package in ("event", "event/world", "region", "simd", "world"):
            self.assertTrue((api / package).is_dir(), package)
        self.assertTrue((api / "Unsupported.java").is_file())
        self.assertTrue((api / "world/WorldUnloadResult.java").is_file())


class CanvasPatchCouplingTest(unittest.TestCase):
    def patch(self, *added):
        return "\n".join(["diff --git a/x.java b/x.java", "--- a/x.java", "+++ b/x.java",
                          "@@ -1,1 +1,2 @@", " context", *added]) + "\n"

    def check(self, *added, series=policy.INTEGRATION_PATCH_ROOTS[0]):
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            layout(root)
            (root / series / "0001-fixture.patch").write_text(self.patch(*added))
            return policy.canvas_patch_symbols(root)

    def test_detects_an_added_call_into_canvas(self):
        self.assertEqual(self.check("+        io.canvasmc.canvas.Engine.tick();"),
                         ["io.canvasmc.canvas.Engine.tick"])

    def test_detects_an_added_call_in_a_paper_feature_patch(self):
        # The Paper feature series is SourbyCraft code too (AGENTS.md); before OBS-7 it was
        # outside the measured surface.
        self.assertEqual(self.check("+        io.canvasmc.canvas.Engine.tick();", series=PAPER_FEATURES),
                         ["io.canvasmc.canvas.Engine.tick"])

    def test_ignores_a_removed_call(self):
        # A patch deleting a Canvas call reduces coupling; it must not count as adding it.
        self.assertEqual(self.check("-        io.canvasmc.canvas.Engine.tick();"), [])

    def test_ignores_a_comment_recording_what_was_removed(self):
        self.assertEqual(self.check("+        // (was: io.canvasmc.canvas.Engine.tick();)"), [])

    def test_ignores_the_file_header(self):
        self.assertEqual(self.check("+++ b/io/canvasmc/canvas/Thing.java"), [])

    def test_the_engine_baseline_is_outside_the_measured_surface(self):
        # minecraft-patches/sources and paper-patches/files are the inherited engine baseline
        # (formerly Canvas/Folia); only SourbyCraft's own feature patches are integration points.
        self.assertEqual(policy.INTEGRATION_PATCH_ROOTS, (
            "sourbycraft-server/minecraft-patches/features",
            "sourbycraft-server/paper-patches/features",
        ))


class RootsTest(unittest.TestCase):
    def test_every_root_is_explicit_and_present(self):
        # No `if is_dir()` here on purpose: a root that vanished must fail, not shrink the scan.
        self.assertEqual(policy.ENGINE_ROOT, "sourbycraft-server/src/main/java/dev/iyanz/aurora/engine")
        for relative in ALL_ROOTS:
            with self.subTest(root=relative):
                self.assertTrue((REPO / relative).is_dir(), relative)

    def test_the_policy_actually_reads_the_repository(self):
        seen = sum(len(list((REPO / root).rglob("*.java"))) for root in policy.SOURCE_ROOTS)
        self.assertGreater(seen, 20)
        for relative in policy.INTEGRATION_PATCH_ROOTS:
            with self.subTest(root=relative):
                self.assertTrue(list((REPO / relative).glob("*.patch")), relative)

    def test_a_missing_root_is_an_error_not_a_skip(self):
        for missing in ALL_ROOTS:
            with self.subTest(missing=missing):
                with tempfile.TemporaryDirectory() as directory:
                    root = Path(directory)
                    layout(root)
                    (root / missing).rmdir()
                    with self.assertRaises(FileNotFoundError) as raised:
                        policy.canvas_source_sites(root)
                        policy.canvas_patch_symbols(root)
                        policy.scheduler_sites(root)
                        policy.scheduler_patch_sites(root)
                    self.assertIn(missing, str(raised.exception))


class SchedulerCouplingTest(unittest.TestCase):
    """The Folia internal region scheduler reached from SourbyCraft's own side.

    This is the surface an Aurora execution contract has to cover. It is pinned so the
    contract is designed against a known list rather than a moving one, and so a new
    reach into the scheduler is a deliberate act with a reason beside it.
    """

    APPROVED = {
        # The Aurora region system's backend adapter: the global tick handle's metrics and the
        # configured tick rate. Read-only; it schedules nothing and mutates nothing.
        "io.papermc.paper.threadedregions.RegionizedServer",
        "io.papermc.paper.threadedregions.TickRegionScheduler",
        "RegionizedServer",
        "TickRegionScheduler",
        # The one adapter behind the Aurora owner-handoff contract. Everything that hands
        # work to an entity's owner goes through OwnerHandoff, so this is the only name.
        "io.papermc.paper.threadedregions.EntityScheduler",
        # /perf scheduler and /perf region <world> <x> <z> (dependency-ledger.md section 1.2,
        # recorded 2026-10-20): the region table and region identity, read without a lock from
        # a command thread. Read-only; neither view schedules or mutates anything.
        "io.papermc.paper.threadedregions.ThreadedRegionizer",
        "io.papermc.paper.threadedregions.TickRegions",
        "ThreadedRegionizer",
        "TickRegions",
        # /perf scheduler queued tasks per region (recorded 2026-10-20, MC feature patch 0045):
        # RegionTaskQueueData's volatile per-queue entry counts, read without the queue monitor.
        # Read-only; the view queues, runs and cancels nothing.
        "io.papermc.paper.threadedregions.RegionizedTaskQueue",
    }

    def test_the_backend_is_named_only_inside_the_adapters(self):
        # The point of the contracts is that replacing the backend is an edit to a known set of
        # files, not a search. Three adapters: one answers "how is the engine ticking", one
        # "hand this to whoever owns that entity", and the AuroraWorlds service runs world
        # creation, unloading and per-region saves on the global tick, as the engine requires.
        files = {site["file"] for site in policy.scheduler_sites(REPO)}
        self.assertEqual(files, {
            "sourbycraft-server/src/main/java/dev/iyanz/sourbycraft/execution/RegionOwnerHandoff.java",
            "sourbycraft-server/src/main/java/dev/iyanz/sourbycraft/execution/region/FoliaRegionBackend.java",
            "sourbycraft-server/src/main/java/dev/iyanz/sourbycraft/awf/world/AuroraWorldsService.java",
            # Read-only diagnostics views (dependency-ledger.md section 1.2).
            "sourbycraft-server/src/main/java/dev/iyanz/sourbycraft/perf/LiveSchedulerSource.java",
            "sourbycraft-server/src/main/java/dev/iyanz/sourbycraft/perf/RegionLocator.java",
        }, "the region backend is named outside the adapters; route it through a contract")

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
            {
                "io.papermc.paper.threadedregions.TickRegionScheduler.getCurrentRegionizedWorldData",
                # Paper feature 0005: Bukkit#getTPS off any region thread answers from the global
                # tick's data instead of throwing (recorded 2026-10-20, scan widened per OBS-7).
                "io.papermc.paper.threadedregions.RegionizedServer.getGlobalTickData",
                # Paper feature 0014: staged createWorld queues the spawn stages on the global tick,
                # as the engine requires for world creation (same reason as AuroraWorldsService).
                "io.papermc.paper.threadedregions.RegionizedServer.getInstance",
            },
            "a feature patch reaches into the region scheduler outside the recorded set")

    def test_the_public_folia_api_is_not_counted_as_coupling(self):
        # threadedregions.scheduler is the published Bukkit-facing API. Depending on a
        # contract is the goal, not the problem.
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            layout(root)
            (root / OWNED / "Fixture.java").write_text(
                "import io.papermc.paper.threadedregions.scheduler.ScheduledTask;\n")
            self.assertEqual(policy.scheduler_sites(root), [])

    def test_a_patch_editing_the_scheduler_is_not_a_call_into_it(self):
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            layout(root)
            (root / policy.INTEGRATION_PATCH_ROOTS[0] / "0001-fixture.patch").write_text(
                "+++ b/io/papermc/paper/threadedregions/TickRegionScheduler.java\n"
                "++ b/io/papermc/paper/threadedregions/TickRegions.java\n")
            self.assertEqual(policy.scheduler_patch_sites(root), [])

    def test_a_patch_site_names_the_series_it_came_from(self):
        # Both feature series number from 0001, so a bare file name would be ambiguous.
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            layout(root)
            (root / PAPER_FEATURES / "0001-fixture.patch").write_text(
                "+++ b/CraftServer.java\n@@ -1,1 +1,2 @@\n context\n"
                "+        io.papermc.paper.threadedregions.RegionizedServer.getInstance();\n")
            self.assertEqual(policy.scheduler_patch_sites(root), [{
                "patch": PAPER_FEATURES + "/0001-fixture.patch",
                "symbol": "io.papermc.paper.threadedregions.RegionizedServer.getInstance"}])


if __name__ == "__main__":
    unittest.main()
