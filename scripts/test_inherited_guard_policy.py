from pathlib import Path
import json
import shutil
import tempfile
import unittest

import inherited_guard_policy as policy

REPO = Path(__file__).resolve().parents[1]
LEDGER = REPO / "docs/architecture/inherited-guards.md"

MC_SOURCES, PAPER_FILES, API_FILES = (root for root, _, _ in policy.BASELINE_ROOTS)
MC_JAVA, PAPER_JAVA, API_JAVA = (root for _, _, root in policy.BASELINE_ROOTS)
MC_FEATURES, PAPER_FEATURES, _ = (root for root, _, _ in policy.FEATURE_ROOTS)


def hunk(*lines, context=("context",)):
    """One diffpatch-format hunk: ``context`` lines, then ``lines`` added."""
    body = "\n".join(" " + line for line in context) + "\n" + "\n".join("+" + line for line in lines)
    return "--- a/X.java\n+++ b/X.java\n@@ -1,1 +_,%d @@\n%s\n" % (len(lines) + len(context), body)


def feature(target, *lines, context=("context",)):
    """A git-format feature patch adding ``lines`` to ``target`` (the `+++ b/` path)."""
    body = "\n".join(" " + line for line in context) + "\n" + "\n".join("+" + line for line in lines)
    return ("From 0 Mon Sep 17 00:00:00 2001\nSubject: [PATCH] SourbyCraft - fixture\n\n"
            f"diff --git a/{target} b/{target}\n--- a/{target}\n+++ b/{target}\n"
            f"@@ -10,1 +10,{len(lines) + len(context)} @@ class X\n{body}\n")


COMMAND_BLOCK = hunk(
    "    protected void tick() {",
    "        // Canvas start - region threading",
    "        if (true) {",
    "            return;",
    "        }",
    "        // Canvas end - region threading",
)
EXECUTOR = hunk(
    "    public void execute(Runnable runnable) {",
    "        if (true) {",
    '            throw new UnsupportedOperationException("Unsupported in region threading");',
    "        }",
    "        super.execute(runnable);",
    "    }",
    "    public int getTickCount() {",
    '        throw new UnsupportedOperationException("Unsupported in region threading"); // Folia - region threading',
    "    }",
    "    void checks(Entity e) {",
    '        ca.spottedleaf.moonrise.common.util.TickThread.ensureTickThread(e, "x"); // Folia - region threading',
    '        ca.spottedleaf.moonrise.common.util.TickThread.ensureTickThread(e, "paper own");',
    '        dev.iyanz.aurora.engine.util.TickGuard.guard(e, "y");',
    "    }",
)
PAPER_OWN = hunk(
    "    void paper() {",
    "        if (true) return; // Paper - rewrite chunk system",
    "        // Folia - region threading",
    "        if (true) { // CraftBukkit",
    "        }",
    '        throw new UnsupportedOperationException("Score holder must be an entity");',
    "    }",
)
API = hunk(
    "    @Unsupported // Canvas - region threading",
    "    boolean teleport(Location location);",
)
ENGINE = hunk(
    "    private void loadTestData() {",
    "        if (true) {",
    "            return;",
    "        }",
)


class Fixture:
    """A temporary repository with one small patch per root (patch mode by default).

    Skips the test, with the reason, where a temporary directory cannot be created (sandboxes
    without a writable temp location).
    """

    def __enter__(self):
        try:
            self._directory = tempfile.TemporaryDirectory()
        except (OSError, PermissionError) as error:
            raise unittest.SkipTest(f"cannot create a temporary directory here: {error}")
        self.root = Path(self._directory.name)
        self.write(f"{MC_SOURCES}/net/minecraft/world/level/block/CommandBlock.java.patch", COMMAND_BLOCK)
        self.write(f"{MC_SOURCES}/net/minecraft/server/MinecraftServer.java.patch", EXECUTOR)
        self.write(f"{MC_SOURCES}/io/papermc/paper/threadedregions/ThreadedRegionizer.java.patch", ENGINE)
        self.write(f"{PAPER_FILES}/src/main/java/org/bukkit/craftbukkit/CraftServer.java.patch", PAPER_OWN)
        self.write(f"{PAPER_FILES}/src/test/java/Ignored.java.patch", EXECUTOR)       # not server code
        self.write(f"{PAPER_FILES}/src/main/resources/log4j2.xml.patch", "+<x/>\n")   # not java
        self.write(f"{API_FILES}/src/main/java/org/bukkit/entity/Entity.java.patch", API)
        for directory in (MC_FEATURES, PAPER_FEATURES, policy.ENGINE_SOURCE_ROOT, "build-data"):
            (self.root / directory).mkdir(parents=True, exist_ok=True)
        return self

    def __exit__(self, *exc):
        self._directory.cleanup()

    def write(self, relative, body):
        path = self.root / relative
        path.parent.mkdir(parents=True, exist_ok=True)
        path.write_text(body)
        return relative

    def materialize(self):
        """Create materialized files from the patches' post-images, switching to materialized mode."""
        for patch, relative in policy.baseline_patches(self.root):
            lines = [line for _, lines in policy.patch_hunks(patch.read_text()) for _, line in lines]
            self.write(relative, "\n".join(lines) + "\n")
        for root in (MC_JAVA, PAPER_JAVA, API_JAVA):
            (self.root / root).mkdir(parents=True, exist_ok=True)

    def decide_all(self, **override):
        policy.write_manifest(self.root, allow_removals=True)
        manifest = json.loads((self.root / policy.MANIFEST).read_text())
        for entry in manifest["guards"]:
            entry.update(kind="FEATURE-DISABLED", decision="KEEP-OWNED", note="fixture")
            entry.update(override)
        for family in manifest["families"]:
            family.update(decision="KEEP-OWNED", note="fixture")
        (self.root / policy.MANIFEST).write_text(json.dumps(manifest))
        return manifest

    def manifest(self):
        return json.loads((self.root / policy.MANIFEST).read_text())


def keys(guards):
    return sorted(policy.guard_key(g) for g in guards)


def anchors(guards, name):
    return [g["anchor"] for g in guards if g["file"].endswith(name)]


class DetectionTest(unittest.TestCase):

    def test_patch_mode_finds_each_guard_shape(self):
        with Fixture() as fx:
            guards, families, mode = policy.scan(fx.root)
            self.assertEqual(mode, "patch")
            found = {(g["file"].split("/")[-1], g["anchor"], g["detector"], g["origin"]) for g in guards}
            self.assertEqual(found, {
                ("CommandBlock.java", "if (true) {", "A", "Canvas"),            # marker block
                ("MinecraftServer.java", "if (true) {", "A", "Folia"),          # region throw below it
                ("MinecraftServer.java",
                 'throw new UnsupportedOperationException("Unsupported in region threading"); // Folia - region threading',
                 "B", "Folia"),
                ("ThreadedRegionizer.java", "if (true) {", "C", "Folia"),       # engine dir, unmarked
                ("Entity.java", "@Unsupported // Canvas - region threading", "D", "Canvas"),
            })
            # Paper's own if (true) lines and an unrelated throw are not guards even next to a marker.
            self.assertFalse(any(g["file"].endswith("CraftServer.java") for g in guards))
            # The throw directly under a matched `if (true)` is the same guard, not a second entry.
            self.assertEqual(sum(1 for g in guards if g["file"].endswith("MinecraftServer.java")), 2)
            self.assertEqual(families["F-TICKTHREAD-ENSURE"], 1)          # only the marked site
            self.assertEqual(families["F-TICKGUARD"], 1)                  # TickGuard counts unmarked

    def test_src_test_and_resource_patches_are_not_scanned(self):
        with Fixture() as fx:
            files = {relative for _, relative in policy.baseline_patches(fx.root)}
            self.assertNotIn(f"{PAPER_JAVA}/src/test/java/Ignored.java", files)
            self.assertFalse(any(f.endswith(".xml") for f in files))

    def test_materialized_and_patch_modes_agree_on_the_fixture(self):
        with Fixture() as fx:
            patch_guards, patch_families, _ = policy.scan(fx.root)
            fx.materialize()
            guards, families, mode = policy.scan(fx.root)
            self.assertEqual(mode, "materialized")
            self.assertEqual(keys(guards), keys(patch_guards))
            self.assertEqual(families, patch_families)

    # Review finding 1: whitespace/paren variants and marked early returns must not evade detection.
    def test_whitespace_and_paren_variants_are_constant_conditions(self):
        with Fixture() as fx:
            fx.write(f"{MC_SOURCES}/net/minecraft/V.java.patch", hunk(
                "    void a() {",
                "        if(true) return; // Folia - region threading",
                "        if ((true)) { // Canvas - region threading",
                "        }",
                "        if (false) while (!q.isEmpty()) { // Folia - region threading",
                "        }",
                "        if (false && connected && (x // Folia - region threading - connection fixes",
                "            || y)) {",
                "        }",
                "    }"))
            self.assertEqual(anchors(policy.scan(fx.root)[0], "V.java"), [
                "if(true) return; // Folia - region threading",
                "if ((true)) { // Canvas - region threading",
                "if (false) while (!q.isEmpty()) { // Folia - region threading",
                "if (false && connected && (x // Folia - region threading - connection fixes",
            ])

    def test_marked_early_return_at_the_top_of_a_method_is_a_guard(self):
        with Fixture() as fx:
            fx.write(f"{MC_SOURCES}/net/minecraft/R.java.patch", hunk(
                "    public boolean isCommandBlockEnabled() {",
                "        return false; // Canvas - region threading",
                "    }",
                "    public BlockPos adjust(ServerLevel level) {",
                "        // Folia start - region threading",
                "        return null;",
                "        // Folia end - region threading",
                "    }",
                "    void notAGuard(Entity e) {",
                "        if (!TickThread.isTickThreadFor(e)) {",
                "            return; // Folia - region threading",      # inside an ownership check
                "        }",
                "        return; // Folia - region threading",          # not the first statement
                "    }"))
            found = [(g["anchor"], g["detector"]) for g in policy.scan(fx.root)[0] if g["file"].endswith("R.java")]
            self.assertEqual(found, [("return false; // Canvas - region threading", "E"), ("return null;", "E")])

    def test_throw_with_any_message_inside_a_marked_block_is_a_guard(self):
        with Fixture() as fx:
            fx.write(f"{MC_SOURCES}/net/minecraft/T.java.patch", hunk(
                "    int list(ScoreHolder entity) {",
                "        // Canvas start - region threading",
                "        if (!(entity instanceof Entity casted)) {",
                '            throw new UnsupportedOperationException("Score holder must be an entity");',
                "        }",
                "        // Canvas end - region threading",
                "    }",
                "    void other() {",
                '        throw new UnsupportedOperationException("plain, unmarked, outside any block");',
                "    }"))
            self.assertEqual(anchors(policy.scan(fx.root)[0], "T.java"),
                             ['throw new UnsupportedOperationException("Score holder must be an entity");'])

    # Review finding 6: comments and strings are not code; `true && x` is not constant.
    def test_commented_out_throws_and_non_constant_conditions_are_ignored(self):
        with Fixture() as fx:
            fx.write(f"{MC_SOURCES}/net/minecraft/C.java.patch", hunk(
                "    void a() {",
                '        // throw new UnsupportedOperationException("Unsupported in region threading"); // Folia',
                '        LOGGER.info("if (true) { // Folia - region threading"); // a string, not code',
                "        if (true && enabled) { // Folia - region threading",
                "        }",
                "        if ((false && a) || b) { // Folia - region threading",
                "        }",
                "        if (false || enabled) { // Folia - region threading",
                "        }",
                "    }"))
            self.assertEqual(anchors(policy.scan(fx.root)[0], "C.java"), [])

    def test_block_comments_spanning_lines_are_not_code(self):
        with Fixture() as fx:
            fx.write(f"{MC_SOURCES}/io/papermc/paper/threadedregions/Dead.java.patch", hunk(
                "    /*",
                "    private void loadTestData() {",
                "        if (true) {",                     # commented-out method: not code
                "            return;",
                "        }",
                "    }",
                "    */",
                "    void live() {",
                "        /* inline */ if (true) { // Folia - region threading",
                "        }",
                "        /* opens here",
                "        if (false) { // Folia - region threading",
                "        } closes here */ if (false) { // Folia - region threading",
                "        }",
                "    }"))
            self.assertEqual(anchors(policy.scan(fx.root)[0], "Dead.java"), [
                "/* inline */ if (true) { // Folia - region threading",
                "} closes here */ if (false) { // Folia - region threading",
            ])
        self.assertEqual(policy.code_lines(["a; /* x", " * y", " z */ b;", "c; // d"]), ["a;", "", " b;", "c;"])

    def test_summary_block_renders_the_manifest_numbers(self):
        with Fixture() as fx:
            fx.decide_all()
            block = policy.render_summary(fx.root)
            self.assertTrue(block.startswith(policy.SUMMARY_BEGIN))
            self.assertTrue(block.rstrip().endswith(policy.SUMMARY_END))
            self.assertIn("| **guards** | **5** | **0** | **0** | **0** | **5** |", block)
            self.assertIn("| `F-TICKTHREAD-ENSURE` | 1 |", block)

    def test_constant_condition_rules(self):
        cases = {"if (true) {": "true", "if(false){": "false", "if ((true)) {": "true",
                 "if (false && x.y()) {": "false", "if (true || resend) {": "true",
                 "if (true && enabled) {": None, "if ((false && a) || b) {": None,
                 "if (x == true) {": None, "} if (false) { // Canvas": "false", "while (true) {": None}
        for code, expected in cases.items():
            with self.subTest(code=code):
                self.assertEqual(policy.constant_condition(code), expected)

    # Review finding 2: SourbyCraft feature patches and the engine sources are in scope.
    def test_feature_patch_guards_are_scanned_with_sourbycraft_origin(self):
        with Fixture() as fx:
            fx.write(f"{PAPER_FEATURES}/0001-SourbyCraft-fixture.patch", feature(
                "src/main/java/com/destroystokyo/paper/Metrics.java",
                "        // SourbyCraft - remove bStats phone-home",
                "        if (true) return;",
                context=("    private void submitData() {",)))
            fx.write(f"{MC_FEATURES}/0001-SourbyCraft-fixture.patch", feature(
                "net/minecraft/server/level/ServerPlayer.java",
                "        // SourbyCraft start - owned refusal",
                '        throw new UnsupportedOperationException("owned");',
                "        // SourbyCraft end",
                context=("    void owned() {",)))
            guards = policy.scan(fx.root)[0]
            found = {(g["file"].split("/")[-1], g["anchor"], g["origin"]) for g in guards
                     if g["file"].endswith(("Metrics.java", "ServerPlayer.java"))}
            self.assertEqual(found, {("Metrics.java", "if (true) return;", "SourbyCraft"),
                                     ("ServerPlayer.java", 'throw new UnsupportedOperationException("owned");', "SourbyCraft")})

    def test_engine_sources_are_scanned_for_refusals(self):
        with Fixture() as fx:
            fx.write(f"{policy.ENGINE_SOURCE_ROOT}/scheduler/Pool.java",
                     "class Pool {\n    public boolean cancel(Task t) {\n"
                     '        throw new UnsupportedOperationException("Unsupported in AFFINITY Scheduler");\n'
                     "    }\n    Node parse(Object o) {\n"
                     '        throw new UnsupportedOperationException("Node was not a MappingNode");\n'   # parser check
                     "    }\n}\n")
            self.assertEqual(anchors(policy.scan(fx.root)[0], "Pool.java"),
                             ['throw new UnsupportedOperationException("Unsupported in AFFINITY Scheduler");'])

    def test_feature_hunk_context_does_not_duplicate_a_baseline_guard(self):
        with Fixture() as fx:
            # A feature patch whose context shows the baseline's `if (true) {` must not add a second entry.
            fx.write(f"{MC_FEATURES}/0002-SourbyCraft-fixture.patch", feature(
                "net/minecraft/world/level/block/CommandBlock.java",
                "        // SourbyCraft - a line after the guard",
                context=("        // Canvas start - region threading", "        if (true) {", "            return;")))
            guards = policy.scan(fx.root)[0]
            self.assertEqual(anchors(guards, "CommandBlock.java"), ["if (true) {"])

    def test_a_feature_patch_that_deletes_a_baseline_guard_retires_it_in_both_modes(self):
        with Fixture() as fx:
            fx.write(f"{MC_FEATURES}/0004-SourbyCraft-fixture.patch", (
                "From 0 Mon Sep 17 00:00:00 2001\nSubject: [PATCH] SourbyCraft - owned command blocks\n\n"
                "diff --git a/net/minecraft/world/level/block/CommandBlock.java b/net/minecraft/world/level/block/CommandBlock.java\n"
                "--- a/net/minecraft/world/level/block/CommandBlock.java\n+++ b/net/minecraft/world/level/block/CommandBlock.java\n"
                "@@ -1,6 +1,3 @@\n     protected void tick() {\n-        // Canvas start - region threading\n"
                "-        if (true) {\n-            return;\n-        }\n-        // Canvas end - region threading\n"
                "+        if (!policy.allows(pos)) return; // SourbyCraft - owned command blocks\n"))
            patch_guards = policy.scan(fx.root)[0]
            self.assertEqual(anchors(patch_guards, "CommandBlock.java"), [])
            fx.materialize()
            fx.write(f"{MC_JAVA}/net/minecraft/world/level/block/CommandBlock.java",
                     "context\n    protected void tick() {\n        if (!policy.allows(pos)) return; // SourbyCraft - owned command blocks\n")
            guards, _, mode = policy.scan(fx.root)
            self.assertEqual(mode, "materialized")
            self.assertEqual(keys(guards), keys(patch_guards))

    def test_a_retired_id_is_not_reused_by_a_new_entry(self):
        with Fixture() as fx:
            fx.decide_all()
            (fx.root / f"{MC_SOURCES}/net/minecraft/world/level/block/CommandBlock.java.patch").write_text(
                hunk("    protected void tick() {", "        // Canvas start - region threading",
                     "        if (false) { // a different guard", "        }", "        // Canvas end - region threading"))
            count, _, removed = policy.write_manifest(fx.root, allow_removals=True)
            self.assertEqual(removed, ["CommandBlock#1"])
            ids = [e["id"] for e in fx.manifest()["guards"] if "CommandBlock" in e["file"]]
            self.assertEqual(ids, ["CommandBlock#2"])

    # Review finding 5: hunks are separate; a marker in one never vouches for a line in another.
    def test_hunk_boundaries_are_kept_in_patch_mode(self):
        with Fixture() as fx:
            two_hunks = ("--- a/X.java\n+++ b/X.java\n"
                         "@@ -1,1 +_,2 @@\n context\n+        // Folia start - region threading\n"
                         "@@ -40,1 +_,2 @@\n other\n+        if (true) return;\n")
            fx.write(f"{MC_SOURCES}/net/minecraft/H.java.patch", two_hunks)
            self.assertEqual(anchors(policy.scan(fx.root)[0], "H.java"), [])
            hunks = policy.patch_hunks(two_hunks)
            self.assertEqual([len(lines) for _, lines in hunks], [2, 2])

    def test_patch_hunks_keep_context_and_added_lines_only(self):
        text = "--- a/A\n+++ b/A\n@@ -1,3 +_,3 @@\n keep\n-gone\n+added\n context\n"
        self.assertEqual(policy.patch_hunks(text), [("@@ -1,3 +_,3 @@", [(False, "keep"), (True, "added"), (False, "context")])])

    # Review finding 3: identical guards in different methods are different keys.
    def test_identical_guards_in_different_methods_have_different_keys(self):
        with Fixture() as fx:
            fx.write(f"{MC_SOURCES}/net/minecraft/Two.java.patch", hunk(
                "    public void a() {", "        if (true) { // Folia - region threading", "        }",
                "    public void b() {", "        if (true) { // Folia - region threading", "        }"))
            two = [g for g in policy.scan(fx.root)[0] if g["file"].endswith("Two.java")]
            self.assertEqual([g["nth"] for g in two], [1, 1])
            self.assertNotEqual(policy.guard_key(two[0]), policy.guard_key(two[1]))
            self.assertEqual([g["method"] for g in two], ["a", "b"])

    def test_a_sourbycraft_block_inserted_above_an_inherited_guard_does_not_rekey_it(self):
        with Fixture() as fx:
            before = policy.scan(fx.root)[0]
            key = [policy.guard_key(g) for g in before if g["file"].endswith("MinecraftServer.java")]
            fx.write(f"{MC_FEATURES}/0003-SourbyCraft-fixture.patch", feature(
                "net/minecraft/server/MinecraftServer.java",
                "        // SourbyCraft start - Aurora Bridge",
                "        if (bridge.handle(runnable)) return;",
                "        // SourbyCraft end - Aurora Bridge",
                context=("    public void execute(Runnable runnable) {",)))
            fx.materialize()
            # Materialize the feature's insertion above the baseline guard by hand.
            path = fx.root / f"{MC_JAVA}/net/minecraft/server/MinecraftServer.java"
            text = path.read_text().replace(
                "    public void execute(Runnable runnable) {\n",
                "    public void execute(Runnable runnable) {\n        // SourbyCraft start - Aurora Bridge\n"
                "        if (bridge.handle(runnable)) return;\n        // SourbyCraft end - Aurora Bridge\n")
            path.write_text(text)
            after = [policy.guard_key(g) for g in policy.scan(fx.root)[0] if g["file"].endswith("MinecraftServer.java")]
            self.assertEqual(after, key)

    def test_a_missing_baseline_root_is_an_error_not_a_skip(self):
        with Fixture() as fx:
            shutil.rmtree(fx.root / API_FILES)
            with self.assertRaises(policy.GuardError):
                policy.scan(fx.root)


class ManifestTest(unittest.TestCase):

    def test_fresh_manifest_has_no_decisions_and_fails_the_check(self):
        with Fixture() as fx:
            count, _, removed = policy.write_manifest(fx.root)
            self.assertEqual((count, removed), (5, []))
            problems = policy.check(fx.root)
            self.assertTrue(any("decision must be one of" in p for p in problems))
            self.assertTrue(any("family F-TICKTHREAD-ENSURE: needs a decision" in p for p in problems))

    def test_decided_manifest_passes(self):
        with Fixture() as fx:
            fx.decide_all()
            self.assertEqual(policy.check(fx.root), [])

    def test_undecided_needs_a_check_and_bad_kind_or_origin_is_refused(self):
        with Fixture() as fx:
            fx.decide_all(decision="UNDECIDED")
            self.assertTrue(all("UNDECIDED needs a `check`" in p for p in policy.check(fx.root)))
            fx.decide_all(decision="UNDECIDED", check="boot and look")
            self.assertEqual(policy.check(fx.root), [])
            fx.decide_all(kind="MYSTERY")
            self.assertTrue(all("kind must be one of" in p for p in policy.check(fx.root)))
            fx.decide_all(origin="Spigot")
            self.assertTrue(all("origin must be one of" in p for p in policy.check(fx.root)))

    def test_a_new_guard_in_the_code_fails_until_recorded(self):
        with Fixture() as fx:
            fx.decide_all()
            fx.write(f"{MC_SOURCES}/net/minecraft/New.java.patch", hunk(
                "    void f() {", "        if (false) { // Folia - region threading", "        }"))
            problems = policy.check(fx.root)
            self.assertEqual(len(problems), 1)
            self.assertIn("guard not in manifest", problems[0])
            self.assertIn("New.java", problems[0])

    def test_a_guard_that_disappeared_from_the_code_fails(self):
        with Fixture() as fx:
            fx.decide_all()
            (fx.root / f"{MC_SOURCES}/net/minecraft/world/level/block/CommandBlock.java.patch").write_text(
                hunk("    protected void tick() {", "    }"))
            problems = policy.check(fx.root)
            self.assertEqual(len(problems), 1)
            self.assertIn("no longer exists in the code", problems[0])

    def test_family_count_drift_fails(self):
        with Fixture() as fx:
            fx.decide_all()
            fx.write(f"{MC_SOURCES}/net/minecraft/Count.java.patch", hunk(
                '        TickThread.ensureTickThread(e, "z"); // Canvas - region threading'))
            problems = policy.check(fx.root)
            self.assertEqual(problems, ["family F-TICKTHREAD-ENSURE: 2 sites in the code, manifest says 1"])

    # Review finding 4: regeneration never drops entries silently and never overwrites a broken file.
    def test_regeneration_keeps_decisions_and_refuses_removals_without_the_flag(self):
        with Fixture() as fx:
            fx.decide_all(note="kept across regen")
            fx.write(f"{MC_SOURCES}/net/minecraft/New.java.patch", hunk(
                "    void f() {", "        if (false) { // Folia - region threading", "        }"))
            policy.write_manifest(fx.root)                     # an addition needs no flag
            notes = {e["id"]: e.get("note") for e in fx.manifest()["guards"]}
            self.assertEqual(notes.pop("New#1"), None)
            self.assertEqual(set(notes.values()), {"kept across regen"})
            (fx.root / f"{MC_SOURCES}/net/minecraft/New.java.patch").unlink()
            with self.assertRaises(policy.GuardError) as refused:
                policy.write_manifest(fx.root)
            self.assertIn("New#1", str(refused.exception))
            self.assertEqual(notes, {e["id"]: e.get("note") for e in fx.manifest()["guards"] if e["id"] != "New#1"})
            count, _, removed = policy.write_manifest(fx.root, allow_removals=True)
            self.assertEqual((count, removed), (5, ["New#1"]))

    def test_a_new_entry_never_takes_or_renames_an_existing_id(self):
        with Fixture() as fx:
            fx.decide_all()
            fx.write(f"{MC_SOURCES}/net/minecraft/world/level/block/CommandBlock.java.patch", hunk(
                "    void earlier() {",
                "        if (false) { // Folia - region threading",          # now the file's first guard
                "        }",
                "    protected void tick() {",
                "        // Canvas start - region threading",
                "        if (true) {",
                "            return;",
                "        }",
                "        // Canvas end - region threading"))
            policy.write_manifest(fx.root)
            ids = {e["anchor"]: e["id"] for e in fx.manifest()["guards"] if "CommandBlock" in e["file"]}
            self.assertEqual(ids, {"if (true) {": "CommandBlock#1", "if (false) { // Folia - region threading": "CommandBlock#2"})

    def test_malformed_manifest_is_never_overwritten(self):
        with Fixture() as fx:
            fx.decide_all()
            path = fx.root / policy.MANIFEST
            path.write_text("{ not json")
            with self.assertRaises(policy.GuardError):
                policy.write_manifest(fx.root, allow_removals=True)
            self.assertEqual(path.read_text(), "{ not json")
            path.write_text(json.dumps({"guards": "nope", "families": []}))
            with self.assertRaises(policy.GuardError):
                policy.check(fx.root)

    def test_old_format_entries_migrate_once_by_file_anchor_and_occurrence(self):
        with Fixture() as fx:
            fx.decide_all(note="from v1")
            manifest = fx.manifest()
            for entry in manifest["guards"]:
                del entry["site"]
                del entry["method"]
            (fx.root / policy.MANIFEST).write_text(json.dumps(manifest))
            problems = policy.check(fx.root)
            self.assertEqual(len(problems), 1)
            self.assertIn("predate the `site` key", problems[0])
            count, _, removed = policy.write_manifest(fx.root)
            self.assertEqual((count, removed), (5, []))
            self.assertEqual({e.get("note") for e in fx.manifest()["guards"]}, {"from v1"})
            self.assertEqual(policy.check(fx.root), [])

    def test_guards_whose_site_names_the_method_follow_the_method_when_reordered(self):
        with Fixture() as fx:
            same = ["        // Canvas start - region threading", "        if (true) {", "            return;", "        }",
                    "        // Canvas end - region threading"]
            fx.write(f"{MC_SOURCES}/net/minecraft/Swap.java.patch", hunk(
                "    void first() {", *same, "    }", "    void second() {", *same, "    }"))
            fx.decide_all()
            manifest = fx.manifest()
            swap = {e["method"]: e for e in manifest["guards"] if e["file"].endswith("Swap.java")}
            swap["first"]["decision"], swap["second"]["decision"] = "REPLACE", "KEEP-OWNED"
            (fx.root / policy.MANIFEST).write_text(json.dumps(manifest))
            fx.write(f"{MC_SOURCES}/net/minecraft/Swap.java.patch", hunk(
                "    void second() {", *same, "    }", "    void first() {", *same, "    }"))
            self.assertEqual(policy.check(fx.root), [])          # the declaration is part of the site
            policy.write_manifest(fx.root)
            after = {e["method"]: e["decision"] for e in fx.manifest()["guards"] if e["file"].endswith("Swap.java")}
            self.assertEqual(after, {"first": "REPLACE", "second": "KEEP-OWNED"})

    def test_reordered_methods_with_identical_guards_and_sites_are_not_silently_remapped(self):
        with Fixture() as fx:
            # Two identical preceding lines in both methods: only order and the method tell them apart.
            same = ["        PacketUtils.ensureRunningOnSameThread(packet, this, this.player.level());",
                    "        // Canvas start - region threading", "        if (true) {", "            return;",
                    "        }", "        // Canvas end - region threading"]
            fx.write(f"{MC_SOURCES}/net/minecraft/Swap.java.patch", hunk(
                "    void first() {", *same, "    }", "    void second() {", *same, "    }"))
            fx.decide_all()
            manifest = fx.manifest()
            swap = [e for e in manifest["guards"] if e["file"].endswith("Swap.java")]
            self.assertEqual([(e["method"], e["nth"]) for e in swap], [("first", 1), ("second", 2)])
            swap[0]["decision"], swap[1]["decision"] = "REPLACE", "KEEP-OWNED"
            (fx.root / policy.MANIFEST).write_text(json.dumps(manifest))
            fx.write(f"{MC_SOURCES}/net/minecraft/Swap.java.patch", hunk(
                "    void second() {", *same, "    }", "    void first() {", *same, "    }"))
            problems = policy.check(fx.root)
            self.assertTrue(any("methods were reordered" in p for p in problems), problems)
            with self.assertRaises(policy.GuardError):
                policy.write_manifest(fx.root)
            policy.write_manifest(fx.root, allow_removals=True)
            decisions = [e.get("decision") for e in fx.manifest()["guards"] if e["file"].endswith("Swap.java")]
            self.assertEqual(decisions, [None, None])

    def test_markdown_lists_every_entry(self):
        with Fixture() as fx:
            fx.decide_all()
            table = policy.render_markdown(fx.root)
            self.assertIn("| `CommandBlock#1` | Canvas | FEATURE-DISABLED | KEEP-OWNED |", table)
            self.assertIn("| `F-TICKGUARD` | 1 | KEEP-OWNED |", table)


class RepositoryTest(unittest.TestCase):
    """The real tree. Runs in the pull-request job too: without materialized sources the scan reads
    the patches, which are always present."""

    def test_every_guard_has_an_owned_decision(self):
        self.assertEqual(policy.check(REPO), [],
                         "a guard changed or is undecided; update docs/architecture/inherited-guards.md and "
                         "build-data/inherited-guards.json (--write-manifest keeps existing decisions)")

    def test_patch_scan_matches_the_materialized_scan(self):
        if not policy.materialized_available(REPO):
            self.skipTest("materialized sources absent (applyAllPatches not run); the patch-mode scan "
                          "is what the check used")
        materialized_guards, materialized_families, mode = policy.scan(REPO)
        self.assertEqual(mode, "materialized")
        original = policy.materialized_available
        policy.materialized_available = lambda root: False
        try:
            patch_guards, patch_families, mode = policy.scan(REPO)
        finally:
            policy.materialized_available = original
        self.assertEqual(mode, "patch")
        self.assertEqual(keys(patch_guards), keys(materialized_guards))
        self.assertEqual(patch_families, materialized_families)

    def test_headline_decisions_are_the_recorded_ones(self):
        # Guards the ledger's summary relies on; a regen must not silently lose them.
        manifest = policy.read_manifest(REPO)
        by_id = {e["id"]: e for e in manifest["guards"]}
        # The seven inherited command-block guards were retired by Minecraft feature 0055 (owned
        # command blocks); what remains of that family is SourbyCraft's own default.
        for retired in ("CommandBlock#1", "CommandBlock#2", "BaseCommandBlock#1", "MinecartCommandBlock#1",
                        "ServerGamePacketListenerImpl#1", "ServerGamePacketListenerImpl#2", "ServerLevel#9"):
            self.assertNotIn(retired, by_id)
        self.assertEqual((by_id["BaseCommandBlock#2"]["origin"], by_id["BaseCommandBlock#2"]["decision"]),
                         ("SourbyCraft", "KEEP-OWNED"))
        # Owned replacements that landed on 2026-10-21 keep their guard as off switch / backstop.
        self.assertEqual(by_id["CraftEntity#1"]["decision"], "KEEP-OWNED")          # owned sync teleport
        self.assertEqual(by_id["PrepareSpawnTask#1"]["decision"], "KEEP-OWNED")     # PlayerSpawnLocationEvent
        self.assertEqual(by_id["MinecraftServer#17"]["decision"], "KEEP-OWNED")     # level-data autosave (0056)
        self.assertEqual(by_id["ServerWaypointManager#1"]["decision"], "KEEP-OWNED")  # override found
        self.assertEqual(by_id["ThrownEnderpearl#1"]["decision"], "KEEP-OWNED")     # PlayerTeleportEvent (MC 0064)
        self.assertEqual(by_id["BukkitScheduler#1"]["decision"], "KEEP-OWNED")
        self.assertEqual(by_id["Metrics#1"]["origin"], "SourbyCraft")
        # Re-worded refusals keep the inherited origin; the block-comment entry is gone for good.
        self.assertEqual((by_id["MCUtil#3"]["origin"], by_id["MCUtil#3"]["decision"]), ("Folia", "KEEP-OWNED"))
        self.assertNotIn("ThreadedRegionizer#1", by_id)
        self.assertNotIn("MCUtil#1", by_id)
        families = {f["id"]: f for f in manifest["families"]}
        self.assertEqual(families["F-TICKGUARD"]["decision"], "KEEP-OWNED")
        self.assertEqual(families["F-TICKTHREAD-ENSURE"]["sites"], 201)

    def test_the_ledger_names_every_manifest_entry(self):
        text = LEDGER.read_text()
        manifest = policy.read_manifest(REPO)
        missing = [e["id"] for e in manifest["guards"] if f"`{e['id']}`" not in text]
        missing += [f["id"] for f in manifest["families"] if f"`{f['id']}`" not in text]
        self.assertEqual(missing, [], "docs/architecture/inherited-guards.md appendix is stale; regenerate it "
                                      "with `python3 scripts/inherited_guard_policy.py --markdown`")

    def test_the_ledger_numbers_are_the_generated_ones(self):
        # Every number in the ledger's summary comes from `--summary`; hand-written counts drifted twice.
        text = LEDGER.read_text()
        self.assertIn(policy.render_summary(REPO), text,
                      "docs/architecture/inherited-guards.md summary block is stale; paste the output of "
                      "`python3 scripts/inherited_guard_policy.py --summary` between its markers")


if __name__ == "__main__":
    unittest.main()
