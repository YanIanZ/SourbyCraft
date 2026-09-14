from pathlib import Path
import tempfile
import unittest

import concurrency_policy as policy

REPO = Path(__file__).resolve().parents[1]
OWNED = "sourbycraft-server/src/main/java/dev/iyanz/sourbycraft"


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
    def check(self, body, name="Fixture.java"):
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            owned = root / OWNED
            owned.mkdir(parents=True)
            (owned / name).write_text(body)
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


class RepositoryTest(unittest.TestCase):
    def test_no_sourby_owned_async_work_runs_on_the_common_pool(self):
        findings = policy.unowned_async_calls(REPO)
        self.assertEqual(findings, [],
                         "async work with no executor runs on ForkJoinPool.commonPool(), which "
                         "SourbyCraft does not own, size, name or shut down (PRD section 45)")

    def test_the_rule_actually_reads_the_repository_sources(self):
        seen = 0
        for source_root in policy.SOURCE_ROOTS:
            base = REPO / source_root
            if base.is_dir():
                seen += len(list(base.rglob("*.java")))
        self.assertGreater(seen, 20, "the policy found almost no sources to check")


if __name__ == "__main__":
    unittest.main()
