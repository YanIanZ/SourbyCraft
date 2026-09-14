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
