from pathlib import Path
import tempfile
import unittest

import patch_policy

REPO = Path(__file__).resolve().parents[1]


def write_patch(root, name, body):
    target = root / "sourbycraft-server" / "minecraft-patches" / "features"
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

    APPROVED = {
        # Console prefix. These are engine configuration, so they speak as the engine:
        # Aurora is inside the Minecraft system, SourbyCraft is the layer outside it.
        "GlobalConfiguration.java.patch:LOGGER": ("LoggerFactory.getLogger(\"CanvasMC\")",
                                                  "LoggerFactory.getLogger(\"Aurora\")"),
        "WorldConfig.java.patch:LOGGER": ("LoggerFactory.getLogger(\"CanvasWorlds\")",
                                          "LoggerFactory.getLogger(\"Aurora\")"),
        # Canvas ships THROW, which crashes the server when a plugin touches state
        # off-region. On a production server that should be logged, not fatal.
        # Operators can restore THROW in config/canvas-server.yml.
        "GlobalConfiguration.java.patch:guardSeverity": ("GuardSeverity.THROW", "GuardSeverity.LOG"),
        # Debug logging on every ender pearl save/load: console spam on an active server.
        "GlobalConfiguration.java.patch:logEnderPearlRewriteActions": ("true", "false"),
        # Canvas' own TPS/RAM bars duplicate the SourbyCraft HUD (/tpsbar, /rambar).
        # Section 23 says keep one implementation, not two.
        "WorldConfig.java.patch:enableTpsBar": ("true", "false"),
        "WorldConfig.java.patch:enableRamBar": ("true", "false"),
    }

    def test_the_shipped_default_changes_are_exactly_the_approved_ones(self):
        found = patch_policy.upstream_default_changes(REPO)
        # LOGGER is changed in two files to the same value; compare by field name.
        self.assertEqual(sorted(found), sorted(self.APPROVED),
                         "a patch changes an upstream default that is not written down; add it "
                         "to APPROVED with the reason, or drop the change")
        for field, transition in found.items():
            with self.subTest(field=field):
                self.assertEqual(transition, self.APPROVED[field])

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
    """Patch 0017's inline block-local AABB must translate all six components.

    Upstream writes `boundingBox.move(-blockX, -blockY, -blockZ)`. 0017 inlines it to
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


class RepositoryTest(unittest.TestCase):
    def test_the_committed_patch_set_holds_no_shared_mutable_buffer(self):
        violations = patch_policy.shared_mutable_fields(REPO)
        self.assertEqual(violations, [], "a patch adds reusable mutable state to a class that "
                                         "more than one region thread reaches; per-region state "
                                         "belongs in RegionizedWorldData")

    def test_the_rule_actually_sees_the_repository_patches(self):
        # Guards against the rule silently passing because it found no files to read.
        self.assertGreater(len(list(patch_policy.patch_files(REPO))), 10)


if __name__ == "__main__":
    unittest.main()
