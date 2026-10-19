from pathlib import Path
import tempfile
import unittest

import concurrency_policy as policy

REPO = Path(__file__).resolve().parents[1]
OWNED = "sourbycraft-server/src/main/java/dev/iyanz/sourbycraft"
ENGINE = "sourbycraft-server/src/main/java/dev/iyanz/aurora/engine"
PAPER_FEATURES = "sourbycraft-server/paper-patches/features"

ALL_ROOTS = policy.SOURCE_ROOTS + policy.PATCH_ROOTS


def layout(root):
    """A fixture tree with every policy root present, since a missing root is an error."""
    for relative in ALL_ROOTS:
        (root / relative).mkdir(parents=True, exist_ok=True)


class ArgumentCountTest(unittest.TestCase):
    def count(self, text):
        return policy._top_level_argument_count(text, text.index("("))

    def test_counts_top_level_arguments(self):
        self.assertEqual(self.count("f()"), 0)
        self.assertEqual(self.count("f(a)"), 1)
        self.assertEqual(self.count("f(a, b)"), 2)

    def test_ignores_commas_nested_inside_the_arguments(self):
        self.assertEqual(self.count("f(g(a, b))"), 1)
        self.assertEqual(self.count("f(() -> g(a, b), executor)"), 2)
        self.assertEqual(self.count("f(new int[]{1, 2, 3})"), 1)

    def test_ignores_commas_inside_literals(self):
        self.assertEqual(self.count('f("a, b")'), 1)
        self.assertEqual(self.count("f(',')"), 1)
        self.assertEqual(self.count('f("\\\\", x)'), 2)

    def test_returns_none_for_an_unterminated_list(self):
        self.assertIsNone(self.count("f(a, b"))


class PolicyTest(unittest.TestCase):
    def check(self, body, name="Fixture.java", root_dir=OWNED):
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            layout(root)
            (root / root_dir / name).write_text(body)
            return policy.unowned_async_calls(root)

    def test_flags_an_async_call_with_no_executor(self):
        found = self.check("class A { void f() { CompletableFuture.supplyAsync(() -> work()); } }")
        self.assertEqual(len(found), 1)
        self.assertEqual(found[0]["kind"], "async-without-executor")
        self.assertIn("supplyAsync", found[0]["detail"])

    def test_accepts_an_async_call_that_names_its_executor(self):
        self.assertEqual(
            self.check("class A { void f() { CompletableFuture.supplyAsync(() -> work(), pool); } }"),
            [])

    def test_accepts_a_multi_line_call_whose_executor_is_lines_below(self):
        # The case that motivated balancing parens: a line rule sees no comma on line one.
        found = self.check("""class A {
    void f() {
        CompletableFuture.supplyAsync(() -> {
            step(a, b);
            step(c, d);
        }, this.executor);
    }
}""")
        self.assertEqual(found, [])

    def test_flags_a_multi_line_call_that_really_has_no_executor(self):
        found = self.check("""class A {
    void f() {
        CompletableFuture.runAsync(() -> {
            step(a, b);
        });
    }
}""")
        self.assertEqual(len(found), 1)

    def test_flags_direct_common_pool_use(self):
        found = self.check("class A { Executor e = ForkJoinPool.commonPool(); }")
        self.assertEqual(len(found), 1)
        self.assertEqual(found[0]["kind"], "commonPool")

    def test_flags_parallel_streams(self):
        self.assertEqual(self.check("class A { void f() { list.parallelStream().forEach(x); } }")[0]["kind"],
                         "parallelStream")
        self.assertEqual(self.check("class A { void f() { list.stream().parallel().count(); } }")[0]["kind"],
                         "parallelStream")

    def test_reports_the_line_number(self):
        found = self.check("class A {\n\n    void f() { CompletableFuture.runAsync(() -> x()); }\n}")
        self.assertEqual(found[0]["line"], 3)

    def test_every_async_variant_is_covered(self):
        for call in policy.ASYNC_CALLS:
            with self.subTest(call=call):
                found = self.check(f"class A {{ void f() {{ future.{call}(x -> y(x)); }} }}")
                self.assertEqual(len(found), 1, call)

    def test_the_engine_package_is_scanned_as_sourby_code(self):
        # Since the migration off Canvas the engine is SourbyCraft's own code; before OBS-7 it
        # was outside the audit.
        found = self.check("class E { void f() { CompletableFuture.runAsync(() -> x()); } }",
                           root_dir=ENGINE)
        self.assertEqual(len(found), 1)
        self.assertTrue(found[0]["file"].startswith(ENGINE))


class PatchPolicyTest(unittest.TestCase):
    """Code SourbyCraft adds to Paper or Minecraft lives in feature patches, not source roots."""

    def check(self, *hunk, series=PAPER_FEATURES):
        text = "\n".join(["diff --git a/src/X.java b/src/X.java", "--- a/src/X.java",
                          "+++ b/src/X.java", "@@ -10,3 +10,6 @@ class X {", *hunk]) + "\n"
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            layout(root)
            (root / series / "0001-fixture.patch").write_text(text)
            return policy.unowned_async_calls(root)

    def test_flags_an_added_async_call_with_no_executor(self):
        found = self.check(" class X {",
                           "+    void f() {",
                           "+        CompletableFuture.runAsync(() -> {",
                           "+            step(a, b);",
                           "+        });",
                           "+    }",
                           " }")
        self.assertEqual(len(found), 1)
        self.assertEqual(found[0]["kind"], "async-without-executor")
        self.assertEqual(found[0]["file"], PAPER_FEATURES + "/0001-fixture.patch")
        self.assertEqual(found[0]["target"], "src/X.java")
        self.assertEqual(found[0]["line"], 12)   # hunk starts at +10; the call is its third line

    def test_accepts_an_added_call_whose_executor_is_on_a_later_added_line(self):
        found = self.check(" class X {",
                           "+    void f() {",
                           "+        CompletableFuture.runAsync(() -> {",
                           "+            step(a, b);",
                           "+        }, executor);",
                           "+    }",
                           " }")
        self.assertEqual(found, [])

    def test_ignores_a_call_that_is_only_context(self):
        # Paper's own call, shown as context around a SourbyCraft change, is not SourbyCraft's.
        found = self.check(" class X {",
                           "     void f() { CompletableFuture.runAsync(() -> x()); }",
                           "+    int added;",
                           " }")
        self.assertEqual(found, [])

    def test_ignores_a_removed_call(self):
        found = self.check(" class X {",
                           "-    void f() { CompletableFuture.runAsync(() -> x()); }",
                           "+    void f() { }",
                           " }")
        self.assertEqual(found, [])

    def test_scans_the_minecraft_feature_series_too(self):
        found = self.check(" class X {",
                           "+    Executor e = ForkJoinPool.commonPool();",
                           " }", series=policy.PATCH_ROOTS[0])
        self.assertEqual([(f["kind"], f["line"]) for f in found], [("commonPool", 11)])


class RootsTest(unittest.TestCase):
    def test_every_root_is_explicit_and_present(self):
        # No `if is_dir()` here on purpose: a root that vanished must fail, not shrink the scan.
        self.assertIn(ENGINE, policy.SOURCE_ROOTS)
        self.assertEqual(policy.PATCH_ROOTS, ("sourbycraft-server/minecraft-patches/features",
                                              "sourbycraft-server/paper-patches/features"))
        for relative in ALL_ROOTS:
            with self.subTest(root=relative):
                self.assertTrue((REPO / relative).is_dir(), relative)

    def test_a_missing_root_is_an_error_not_a_skip(self):
        for missing in ALL_ROOTS:
            with self.subTest(missing=missing):
                with tempfile.TemporaryDirectory() as directory:
                    root = Path(directory)
                    layout(root)
                    (root / missing).rmdir()
                    with self.assertRaises(FileNotFoundError) as raised:
                        policy.unowned_async_calls(root)
                    self.assertIn(missing, str(raised.exception))


class RepositoryTest(unittest.TestCase):
    def test_no_sourby_owned_async_work_runs_on_the_common_pool(self):
        findings = policy.unowned_async_calls(REPO)
        self.assertEqual(findings, [],
                         "async work with no executor runs on ForkJoinPool.commonPool(), which "
                         "SourbyCraft does not own, size, name or shut down (PRD section 45)")

    def test_the_rule_actually_reads_the_repository_sources(self):
        for source_root in policy.SOURCE_ROOTS:
            with self.subTest(root=source_root):
                self.assertGreater(len(list((REPO / source_root).rglob("*.java"))), 1,
                                   "the policy found almost no sources to check")
        self.assertGreater(len(list((REPO / ENGINE).rglob("*.java"))), 50)
        for patch_root in policy.PATCH_ROOTS:
            with self.subTest(root=patch_root):
                self.assertTrue(list((REPO / patch_root).glob("*.patch")), patch_root)


if __name__ == "__main__":
    unittest.main()
