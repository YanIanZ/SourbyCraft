"""Pins the Build 49 reproducible slim jar wiring (no Gradle, no network).

What is pinned here is structure: that the slim jar writer sets its entry times from
SOURCE_DATE_EPOCH, that the workflow stamps builds with it, pins one exact Temurin build in both
building jobs, runs an independent `rebuild` job only for pushes/dispatch, and attests the release.
Whether two builds really produce the same bytes is shown by comparing SHA-256 values (CI's
`rebuild` job; the local two-build proof in docs/releases/26.2-build-49.md), not by this file.
"""
import re
import unittest
from pathlib import Path

ROOT = Path(__file__).resolve().parent.parent
WORKFLOW = (ROOT / ".github" / "workflows" / "build.yml").read_text(encoding="utf-8")
GRADLE = (ROOT / "build.gradle.kts").read_text(encoding="utf-8")


def job(name):
    """The text of one top-level job of the workflow (up to the next two-space-indented key)."""
    match = re.search(r"^  %s:\n(.*?)(?=^  [A-Za-z0-9_-]+:\n|\Z)" % re.escape(name), WORKFLOW, re.S | re.M)
    assert match, "no job " + name
    return match.group(1)


def slim_task():
    start = GRADLE.index('val slimServerJar = tasks.register("slimServerJar")')
    return GRADLE[start:GRADLE.index("val paperMavenPublicUrl", start)]


class SlimJarWriterTest(unittest.TestCase):
    def test_entries_get_a_fixed_time_from_source_date_epoch(self):
        task = slim_task()
        self.assertIn('providers.environmentVariable("SOURCE_DATE_EPOCH")', task)
        self.assertIn('inputs.property("sourceDateEpoch"', task, "a changed epoch must re-run the task")
        self.assertIn("setTimeLocal(", task, "setTime would depend on the machine's time zone")
        self.assertNotRegex(task, r"JarEntry\(name\)\s*\n\s*jos\.putNextEntry\(JarEntry\(name\)\)",
                            "an entry written without a time takes the clock")
        self.assertNotIn("Instant.now()", task)
        self.assertNotIn("System.currentTimeMillis", task)

    def test_the_time_is_clamped_to_the_zip_range(self):
        task = slim_task()
        self.assertIn("315532800L", task, "1980-01-01T00:00:00Z, the DOS epoch")
        self.assertIn("coerceIn(dosEpochSecond, dosMaxSecond)", task)

    def test_entry_order_does_not_depend_on_the_source_jar(self):
        task = slim_task()
        self.assertIn("jar.entries().toList().sortedWith(", task)
        self.assertIn("META-INF/MANIFEST.MF", task)
        self.assertNotIn("while (entries.hasMoreElements())", task, "iterating the source jar's own order")

    def test_a_stamped_build_omits_the_nondeterministic_local_variable_table(self):
        # javac names a lambda's synthetic temporary after an identity hash (EntityCommand.class differed
        # between two clean builds); -g:source,lines drops the LocalVariableTable that carries the name.
        self.assertIn('val reproducibleBuild = providers.environmentVariable("SOURCE_DATE_EPOCH").isPresent', GRADLE)
        self.assertIn('if (reproducibleBuild) options.debugOptions.debugLevel = "source,lines"', GRADLE)

    def test_build_timestamp_honours_source_date_epoch(self):
        self.assertRegex(GRADLE, r'System\.getenv\("SOURCE_DATE_EPOCH"\)')
        self.assertIn('inputs.property("sourceDateEpoch"', GRADLE)


class WorkflowTest(unittest.TestCase):
    def test_build_job_stamps_with_the_commit_time(self):
        build = job("build")
        self.assertIn("git log -1 --format=%ct", build)
        self.assertIn('echo "SOURCE_DATE_EPOCH=$epoch" >> "$GITHUB_ENV"', build)
        self.assertLess(build.index("SOURCE_DATE_EPOCH=$epoch"), build.index("applyAllPatches"),
                        "the epoch must be exported before any Gradle step")

    def test_build_job_outputs_what_the_rebuild_compares(self):
        build = job("build")
        self.assertRegex(build, r"slim_sha256: \$\{\{ steps\.slim\.outputs\.sha256 \}\}")
        self.assertRegex(build, r"source_epoch: \$\{\{ steps\.epoch\.outputs\.epoch \}\}")
        self.assertIn("sha256sum build/libs/SourbyCraft-slim.jar", build)

    def test_one_exact_temurin_build_in_both_building_jobs(self):
        versions = {}
        for name in ("build", "rebuild"):
            found = re.findall(r"actions/setup-java@[0-9a-f]{40}.*?\n\s+with:\n\s+java-version: (\S+)\n\s+distribution: temurin",
                               job(name), re.S)
            self.assertEqual(1, len(found), name)
            versions[name] = found[0]
        self.assertEqual(versions["build"], versions["rebuild"])
        self.assertRegex(versions["build"], r"^25\.\d+\.\d+(\.\d+)?\+\d+$", "a bare 25 floats with the runner image")

    def test_rebuild_job_is_push_only_and_least_privilege(self):
        rebuild = job("rebuild")
        self.assertRegex(rebuild, r"needs: build\n")
        self.assertIn("github.event_name != 'pull_request'", rebuild)
        self.assertRegex(rebuild, r"permissions:\n\s+contents: read\n")
        self.assertNotIn("write", rebuild.split("steps:")[0])

    def test_rebuild_job_rebuilds_the_same_commit_with_the_same_flags(self):
        rebuild, build = job("rebuild"), job("build")
        self.assertIn("ref: ${{ github.sha }}", rebuild)
        self.assertIn('git checkout -B "$GITHUB_REF_NAME"', rebuild, "gitBranch is recorded in the jar")
        for needle in ("SOURBYPATCHER_DEPLOY_KEY", "SOURBYCLIP_DEPLOY_KEY", "private_toolchain.py --publish",
                       "setup_metal.sh", "applyAllPatches"):
            self.assertIn(needle, rebuild)
            self.assertIn(needle, build)
        self.assertIn("-PsourbyBuild=${{ needs.build.outputs.number }}", rebuild)
        self.assertIn("-PsourbyChannel=REL", rebuild)
        self.assertIn("slimServerJar", rebuild)

    def test_rebuild_fails_on_a_mismatch_and_prints_the_entry_diff(self):
        rebuild = job("rebuild")
        self.assertIn("unzip -l", rebuild)
        self.assertIn("diff -u", rebuild)
        self.assertRegex(rebuild, r"NOT REPRODUCIBLE[\s\S]*exit 1")

    def test_nothing_untracked_is_written_into_the_checkout_before_the_slim_build(self):
        # treeHash covers untracked, non-ignored files; the rebuild job's checkout starts without them.
        build = job("build")
        self.assertNotRegex(build, r"[>\s]relnum\.(out|err)\b", "release_version output belongs in $RUNNER_TEMP")
        self.assertIn("scripts/jar_diff.py", job("rebuild"))

    def test_release_waits_for_the_rebuild_and_attests(self):
        release = job("release")
        self.assertRegex(release, r"needs: \[build, rebuild\]")
        self.assertRegex(release, r"actions/attest-build-provenance@[0-9a-f]{40} # v\d")
        head = release.split("steps:")[0]
        self.assertIn("id-token: write", head)
        self.assertIn("attestations: write", head)

    def test_only_the_release_job_may_attest(self):
        for name in ("pr-checks", "build", "rebuild"):
            body = job(name)
            self.assertNotIn("id-token", body)
            self.assertNotIn("attestations", body)
        self.assertNotRegex(WORKFLOW.split("\njobs:\n")[0], r"id-token|attestations")

    def test_every_action_stays_pinned_by_sha(self):
        for line in WORKFLOW.splitlines():
            match = re.match(r"\s+uses: (\S+)", line)
            if match:
                self.assertRegex(match.group(1), r"@[0-9a-f]{40}$", line)

    def test_secrets_never_reach_pull_requests(self):
        pr = job("pr-checks")
        self.assertNotIn("secrets.", pr)
        self.assertIn("github.event_name == 'pull_request'", pr)


if __name__ == "__main__":
    unittest.main()
