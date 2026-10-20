from pathlib import Path
import contextlib
import io
import tempfile
import unittest

import patch_policy

REPO = Path(__file__).resolve().parents[1]

MINECRAFT_FEATURES = "sourbycraft-server/minecraft-patches/features"


def write_patch(root, name, body, directory=MINECRAFT_FEATURES):
    target = root / directory
    target.mkdir(parents=True, exist_ok=True)
    (target / name).write_text(body)
    return root


def patch_for(target_file, *added):
    lines = [f"diff --git a/{target_file} b/{target_file}",
             f"--- a/{target_file}", f"+++ b/{target_file}", "@@ -1,1 +1,2 @@", " context"]
    lines += list(added)
    return "\n".join(lines) + "\n"


class SharedMutableFieldTest(unittest.TestCase):
    SERVER_LEVEL = "net/minecraft/server/level/ServerLevel.java"

    def check(self, *patches):
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            for index, body in enumerate(patches):
                write_patch(root, f"{index:04d}-fixture.patch", body)
            return patch_policy.shared_mutable_fields(root)

    def test_catches_the_worldborder_set_that_actually_shipped(self):
        found = self.check(patch_for(
            self.SERVER_LEVEL,
            "+    private final it.unimi.dsi.fastutil.objects.ReferenceOpenHashSet<WorldBorder>"
            " worldBordersScratch = new it.unimi.dsi.fastutil.objects.ReferenceOpenHashSet<>();"))
        self.assertEqual(len(found), 1)
        self.assertIn("worldBordersScratch", found[0]["declaration"])

    def test_catches_the_block_event_list_that_actually_shipped(self):
        found = self.check(patch_for(
            self.SERVER_LEVEL,
            "+    private final List<BlockEventData> blockEventsToRescheduleScratch = new ArrayList<>(64);"))
        self.assertEqual(len(found), 1)

    def test_catches_a_buffer_whose_name_says_nothing(self):
        # The point of checking types rather than names: the previous guard only rejected
        # fields containing "scratch", so this would have passed it.
        found = self.check(patch_for(self.SERVER_LEVEL,
                                     "+    private final List<Entity> pending = new ArrayList<>();"))
        self.assertEqual(len(found), 1)

    def test_catches_a_static_field_and_an_array_field(self):
        self.assertEqual(len(self.check(patch_for(
            self.SERVER_LEVEL, "+    static final Map<Long, Entity> SHARED = new HashMap<>();"))), 1)
        self.assertEqual(len(self.check(patch_for(
            self.SERVER_LEVEL, "+    private final long[] buckets = new long[64];"))), 1)

    def test_ignores_a_method_local(self):
        # Twelve spaces of indentation: inside a method body, one per call, not shared.
        found = self.check(patch_for(
            self.SERVER_LEVEL,
            "+            final BlockPos.MutableBlockPos pos = new BlockPos.MutableBlockPos();"))
        self.assertEqual(found, [])

    def test_ignores_an_immutable_or_scalar_field(self):
        for declaration in ("+    private final long lastSaveNanos = 0L;",
                            "+    private final String brand = \"SourbyCraft\";",
                            "+    private volatile boolean sourbyEnabled;"):
            with self.subTest(declaration=declaration):
                self.assertEqual(self.check(patch_for(self.SERVER_LEVEL, declaration)), [])

    def test_ignores_a_buffer_on_a_class_owned_by_one_region(self):
        # An entity is owned by one region at a time, so a per-entity buffer is confined.
        found = self.check(patch_for("net/minecraft/world/entity/Entity.java",
                                     "+    private final List<AABB> collisionScratch = new ArrayList<>();"))
        self.assertEqual(found, [])

    def test_reports_the_patch_and_the_declaration(self):
        found = self.check(patch_for(self.SERVER_LEVEL,
                                     "+    private final Set<Long> seen = new HashSet<>();"))
        self.assertEqual(found[0]["file"], self.SERVER_LEVEL)
        self.assertTrue(found[0]["patch"].endswith(".patch"))
        self.assertIn("Set<Long> seen", found[0]["declaration"])

    def test_removed_lines_are_not_violations(self):
        body = patch_for(self.SERVER_LEVEL,
                         "-    private final List<Entity> gone = new ArrayList<>();")
        self.assertEqual(self.check(body), [])


class UpstreamDefaultTest(unittest.TestCase):
    """Every upstream default SourbyCraft ships differently, and why.

    These patches are keyed by target file — one patch per file — so a default change
    cannot be moved into a patch of its own. Pinning the set here is what makes it
    reviewable, and makes a new one fail until somebody writes down the reason.
    """

    # Upstream defaults the SourbyCraft feature patches change. None at the moment.
    APPROVED = {}

    def test_the_shipped_default_changes_are_exactly_the_approved_ones(self):
        found = patch_policy.upstream_default_changes(REPO)
        self.assertEqual(sorted(found), sorted(self.APPROVED),
                         "a patch changes an upstream default that is not written down; add it "
                         "to APPROVED with the reason, or drop the change")
        for field, transition in found.items():
            with self.subTest(field=field):
                self.assertEqual(transition, self.APPROVED[field])

    ENGINE_CONFIG = "sourbycraft-server/src/main/java/dev/iyanz/aurora/engine/"

    # Engine configuration defaults SourbyCraft ships differently from Canvas, which these
    # classes came from. They were canvas-patches before Paper became the only upstream; now
    # they are owned source, so the shipped value is pinned here instead of diffed.
    ENGINE_DEFAULTS = {
        # Console prefix. These are engine configuration, so they speak as the engine:
        # Aurora is inside the Minecraft system, SourbyCraft is the layer outside it.
        ("GlobalConfiguration.java", "LOGGER"): 'LoggerFactory.getLogger("Aurora")',
        ("WorldConfig.java", "LOGGER"): 'LoggerFactory.getLogger("Aurora")',
        # Canvas shipped THROW, which crashes the server when a plugin touches state
        # off-region. On a production server that should be logged, not fatal.
        # Operators can restore THROW in config/canvas-server.yml.
        ("GlobalConfiguration.java", "guardSeverity"): "GuardSeverity.LOG",
        # Canvas logged every ender pearl save/load: console spam on an active server.
        ("GlobalConfiguration.java", "logEnderPearlRewriteActions"): "false",
        # Canvas' own TPS/RAM bars duplicate the SourbyCraft HUD (/tpsbar, /rambar).
        # Section 23 says keep one implementation, not two.
        ("WorldConfig.java", "enableTpsBar"): "false",
        ("WorldConfig.java", "enableRamBar"): "false",
    }

    def test_engine_config_ships_the_recorded_defaults(self):
        for (source, field), value in self.ENGINE_DEFAULTS.items():
            with self.subTest(source=source, field=field):
                defaults = patch_policy.source_field_defaults(REPO, self.ENGINE_CONFIG + source)
                self.assertEqual(defaults.get(field), value,
                                 "an engine configuration default changed; record the new "
                                 "value and the reason in ENGINE_DEFAULTS, or revert it")

    def test_detects_a_default_change_in_a_fixture(self):
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            write_patch(root, "0001-fixture.patch", patch_for(
                "net/minecraft/server/level/ServerLevel.java",
                "-    public int viewDistance = 10;",
                "+    public int viewDistance = 4;"))
            found = patch_policy.upstream_default_changes(root)
            self.assertEqual(found, {"0001-fixture.patch:viewDistance": ("10", "4")})

    def test_an_unchanged_value_is_not_a_change(self):
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            write_patch(root, "0001-fixture.patch", patch_for(
                "net/minecraft/server/level/ServerLevel.java",
                "-    public int viewDistance = 10; // old comment",
                "+    public int viewDistance = 10; // new comment"))
            self.assertEqual(patch_policy.upstream_default_changes(root), {})


class TranslatedBoundsTest(unittest.TestCase):
    """Patch 0012's inline block-local AABB must translate all six components.

    Upstream writes `boundingBox.move(-blockX, -blockY, -blockZ)`. 0012 inlines it to
    drop an allocation. The translation went missing twice while that was being settled,
    and an untranslated box makes isInWall test the wrong region of space — suffocation
    decided against blocks the entity is not in. Silent, not a crash.
    """

    def check(self, expression):
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            write_patch(root, "0017-fixture.patch", patch_for(
                "net/minecraft/world/entity/Entity.java",
                f"+    final AABB toCollide = new AABB({expression});"))
            return patch_policy.translated_bounds(root)

    def test_the_shipped_patch_translates_every_axis(self):
        found = patch_policy.translated_bounds(REPO)
        self.assertEqual(len(found), 1, "expected exactly one inline toCollide construction")
        self.assertEqual(found[0]["translated_axes"], {"x": 2, "y": 2, "z": 2},
                         "each axis must be translated for both the min and the max corner; "
                         "an untranslated corner makes isInWall test the wrong blocks")

    def test_detects_a_box_left_in_world_space(self):
        # The regression that shipped: bounds never moved into block-local space.
        found = self.check("minX, minY, minZ, maxX, maxY, maxZ")
        self.assertEqual(found[0]["translated_axes"], {"x": 0, "y": 0, "z": 0})

    def test_detects_a_partially_translated_box(self):
        found = self.check("minX - blockX, minY, minZ - blockZ, "
                           "maxX - blockX, maxY, maxZ - blockZ")
        self.assertEqual(found[0]["translated_axes"], {"x": 2, "y": 0, "z": 2})

    def test_detects_only_one_corner_translated(self):
        found = self.check("minX - blockX, minY - blockY, minZ - blockZ, maxX, maxY, maxZ")
        self.assertEqual(found[0]["translated_axes"], {"x": 1, "y": 1, "z": 1})

    def test_reads_a_construction_split_across_lines(self):
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            write_patch(root, "0017-fixture.patch", patch_for(
                "net/minecraft/world/entity/Entity.java",
                "+    final AABB toCollide = new AABB(",
                "+        minX - blockX, minY - blockY, minZ - blockZ,",
                "+        maxX - blockX, maxY - blockY, maxZ - blockZ);"))
            found = patch_policy.translated_bounds(root)
            self.assertEqual(found[0]["translated_axes"], {"x": 2, "y": 2, "z": 2})


class ScannedRootTest(unittest.TestCase):
    """Which patch files the rules read.

    The rules walk the three patch trees (audit 2026-10-14 OBS-7 was a root tuple that silently
    skipped locations it did not know about) and read every `*.patch` in them except the
    inherited engine baseline, which belongs to `scripts/baseline_policy.py`. The non-build
    directories under `minecraft-patches` are covered by that walk on purpose: a parked patch
    is one file move from shipping, and a patch dropped in `todo/` or `resources/` must not sit
    outside the rules.
    """

    SERVER_LEVEL = "net/minecraft/server/level/ServerLevel.java"
    PLANTED = "+    private final List<Entity> planted = new ArrayList<>();"
    API_FEATURES = "sourbyapi/paper-patches/features"
    PARKED = "sourbycraft-server/minecraft-patches/parked"
    TODO = "sourbycraft-server/minecraft-patches/todo"
    RESOURCES = "sourbycraft-server/minecraft-patches/resources"

    @staticmethod
    def trees(root):
        for tree in patch_policy.PATCH_TREES:
            (root / tree).mkdir(parents=True, exist_ok=True)
        return root

    def scan(self, directory):
        with tempfile.TemporaryDirectory() as tmp:
            root = self.trees(Path(tmp))
            write_patch(root, "0001-fixture.patch",
                        patch_for(self.SERVER_LEVEL, self.PLANTED), directory=directory)
            return patch_policy.shared_mutable_fields(root)

    def test_the_third_feature_root_is_scanned(self):
        # sourbyapi/paper-patches/features: the location both policy scripts missed (OBS-7).
        self.assertEqual(len(self.scan(self.API_FEATURES)), 1)

    def test_a_paper_api_patch_obeyed_the_same_rules(self):
        with tempfile.TemporaryDirectory() as tmp:
            root = self.trees(Path(tmp))
            write_patch(root, "0001-fixture.patch", patch_for(
                "io/papermc/paper/ServerBuildInfoImpl.java",
                "-    public int viewDistance = 10;",
                "+    public int viewDistance = 4;"), directory=self.API_FEATURES)
            self.assertEqual(patch_policy.upstream_default_changes(root),
                             {"0001-fixture.patch:viewDistance": ("10", "4")})

    def test_the_inventory_names_the_paper_api_path(self):
        with tempfile.TemporaryDirectory() as tmp:
            root = self.trees(Path(tmp))
            write_patch(root, "0001-fixture.patch", patch_for(self.SERVER_LEVEL),
                        directory=self.API_FEATURES)
            self.assertEqual([p.relative_to(root).as_posix() for p in patch_policy.patch_files(root)],
                             [f"{self.API_FEATURES}/0001-fixture.patch"])

    def test_parked_patches_are_scanned(self):
        self.assertEqual(len(self.scan(self.PARKED)), 1)

    def test_scratch_directories_are_scanned(self):
        for directory in (self.TODO, self.RESOURCES):
            with self.subTest(directory=directory):
                self.assertEqual(len(self.scan(directory)), 1)

    def test_the_engine_baseline_is_excluded(self):
        # Not a SourbyCraft change; baseline_policy.py and the manifest own it.
        for directory in patch_policy.BASELINE_DIRS:
            with self.subTest(directory=directory):
                self.assertEqual(self.scan(directory), [])

    def test_a_missing_tree_is_reported_rather_than_skipped(self):
        with tempfile.TemporaryDirectory() as tmp:
            root = Path(tmp)
            write_patch(root, "0001-fixture.patch", patch_for(self.SERVER_LEVEL))
            self.assertEqual(patch_policy.missing_trees(root),
                             ["sourbycraft-server/paper-patches", "sourbyapi/paper-patches"])
            self.assertEqual(len(list(patch_policy.patch_files(root))), 1)

    def test_the_command_fails_a_fixture_violation(self):
        with tempfile.TemporaryDirectory() as tmp:
            root = self.trees(Path(tmp))
            write_patch(root, "0001-fixture.patch", patch_for(self.SERVER_LEVEL, self.PLANTED))
            stderr = io.StringIO()
            with contextlib.redirect_stderr(stderr):
                code = patch_policy.main(["--root", str(root)])
            self.assertEqual(code, 1)
            self.assertIn("planted", stderr.getvalue())


class NetworkCompressionTest(unittest.TestCase):
    """DEV-H P0: no adaptive compression. Nothing SourbyCraft ships may reach the protocol
    compression setup; the threshold stays the operator's `network-compression-threshold`."""

    CONNECTION = "net/minecraft/network/Connection.java"

    def test_the_repository_touches_no_protocol_compression_setup(self):
        self.assertEqual(patch_policy.network_compression_references(REPO), [],
                         "a patch or owned source reaches CompressionEncoder/Decoder, "
                         "setupCompression or the compression threshold; compression settings "
                         "are operator-owned")

    def test_detects_an_adaptive_threshold_in_a_patch(self):
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            write_patch(root, "0001-fixture.patch", patch_for(
                self.CONNECTION,
                "+        if (lagging) this.setupCompression(threshold / 2, false); // SourbyCraft"))
            found = patch_policy.network_compression_references(root)
            self.assertEqual([location for location, _ in found], ["0001-fixture.patch"])

    def test_detects_a_threshold_rewrite_in_owned_source(self):
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            source = root / patch_policy.OWNED_SOURCE / "dev/iyanz/sourbycraft/perf/Tuner.java"
            source.parent.mkdir(parents=True)
            source.write_text("class Tuner {\n    void tune() {\n"
                              "        properties.setProperty(\"network-compression-threshold\", \"512\");\n"
                              "    }\n}\n")
            found = patch_policy.network_compression_references(root)
            self.assertEqual([location for location, _ in found],
                             ["sourbycraft-server/src/main/java/dev/iyanz/sourbycraft/perf/Tuner.java:3"])

    def test_a_removed_line_and_a_comment_about_wire_bytes_are_not_violations(self):
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            write_patch(root, "0001-fixture.patch", patch_for(
                self.CONNECTION,
                "-        this.setupCompression(threshold, false);",
                "+        counters.bytesOut(n); // wire bytes, after compression"))
            self.assertEqual(patch_policy.network_compression_references(root), [])

    def test_the_command_fails_a_fixture_violation(self):
        with tempfile.TemporaryDirectory() as tmp:
            root = Path(tmp)
            for tree in patch_policy.PATCH_TREES:
                (root / tree).mkdir(parents=True)
            write_patch(root, "0001-fixture.patch", patch_for(
                self.CONNECTION, "+        this.setupCompression(64, false);"))
            stderr = io.StringIO()
            with contextlib.redirect_stderr(stderr):
                code = patch_policy.main(["--root", str(root)])
            self.assertEqual(code, 1)
            self.assertIn("operator-owned", stderr.getvalue())


class RepositoryTest(unittest.TestCase):
    def test_the_committed_patch_set_holds_no_shared_mutable_buffer(self):
        violations = patch_policy.shared_mutable_fields(REPO)
        self.assertEqual(violations, [], "a patch adds reusable mutable state to a class that "
                                         "more than one region thread reaches; per-region state "
                                         "belongs in RegionizedWorldData")

    def test_the_rule_actually_sees_the_repository_patches(self):
        # Guards against the rule silently passing because it found no files to read.
        self.assertGreater(len(list(patch_policy.patch_files(REPO))), 10)

    def test_every_patch_tree_is_present(self):
        self.assertEqual(patch_policy.missing_trees(REPO), [],
                         "a patch tree disappeared; the policy would silently stop reading it")

    def test_the_walk_reads_every_patch_outside_the_baseline(self):
        # Recomputed here from the trees, so a directory the walk skips shows up as a diff.
        expected = set()
        for tree in patch_policy.PATCH_TREES:
            for path in (REPO / tree).rglob("*.patch"):
                relative = path.relative_to(REPO).as_posix()
                if not any(relative.startswith(baseline + "/")
                           for baseline in patch_policy.BASELINE_DIRS):
                    expected.add(relative)
        found = {p.relative_to(REPO).as_posix() for p in patch_policy.patch_files(REPO)}
        self.assertEqual(found, expected)

    def test_the_parked_set_is_part_of_the_audit(self):
        # Non-vacuous today: parked/ holds 0028 (LeafPile-derived EDF backend, 2026-10-07). When
        # parked/ is deliberately emptied, delete this assertion with it rather than loosen it.
        parked = [p.name for p in patch_policy.patch_files(REPO) if "parked" in p.parts]
        self.assertEqual(parked,
                         ["0028-SourbyCraft-use-LeafPile-derived-Aurora-EDF-backend.patch"])

    def test_the_command_reports_the_clean_tree(self):
        stderr = io.StringIO()
        with contextlib.redirect_stderr(stderr):
            code = patch_policy.main(["--root", str(REPO)])
        self.assertEqual(code, 0, stderr.getvalue())


if __name__ == "__main__":
    unittest.main()
