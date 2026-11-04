"""Pins the Build 55 sourbyapi release asset in .github/workflows/build.yml (no Gradle, no network).

Every v26.2-rN release carries ONE sourbyapi asset, sourbyapi-<sourbyBuild>.zip, holding sourbyapi-<N>.jar,
-sources.jar, -javadoc.jar and a README.txt. It is a zip on purpose: SourbyUpdater of Builds 51-54 takes the
first asset ending in .jar as the server jar, so a release must carry no .jar asset besides the server jar.
What is pinned is structure: the build job builds the three Gradle tasks, packages and verifies the zip and hands
it over in the existing release artifact; the release job attests it and attaches it to `gh release create`.
Whether the jars contain the right classes is checked by the staging step in CI, not here.
"""
import unittest

from test_reproducible_build import WORKFLOW, job


class SourbyApiReleaseAssetsTest(unittest.TestCase):
    def test_build_job_builds_the_three_api_jars_with_the_release_flags(self):
        build = job("build")
        self.assertRegex(build, r":sourbyapi:jar :sourbyapi:sourcesJar :sourbyapi:javadocJar --no-daemon")
        step = build[build.index("Build the sourbyapi jars"):]
        step = step[:step.index("- name: Show build info")]
        self.assertIn("-PsourbyBuild=${{ steps.relnum.outputs.number }}", step)
        self.assertIn("-PsourbyChannel=REL", step)
        # The test step earlier in the job built sourbyapi-26.2-DEV.jar (no relnum, no REL channel) into the same
        # directory; the API build step clears it first and then requires the three jars of this run's channel.
        self.assertLess(step.index("rm -f sourbyapi/build/libs/*.jar"), step.index("./gradlew :sourbyapi:jar"))
        self.assertIn("channel=${{ steps.relnum.outputs.channel }}", step)
        self.assertIn('test -f "sourbyapi/build/libs/sourbyapi-26.2-$channel$suffix.jar"', step)

    def test_api_jars_are_built_after_the_slim_jar_so_they_cannot_change_it(self):
        build = job("build")
        self.assertLess(build.index("slimServerJar --no-daemon"), build.index(":sourbyapi:javadocJar"))

    def test_the_zip_is_staged_under_the_build_number_only_when_publishing(self):
        build = job("build")
        stage = build[build.index("Stage the sourbyapi release asset"):]
        stage = stage[:stage.index("- name: Hand the verified jar")]
        self.assertIn("if: steps.relnum.outputs.publish == 'true'", stage)
        self.assertIn("release/sourbyapi-$RELEASE_NUMBER.zip", stage)
        # The jars are selected by exact name (26.2-<channel>), never by glob: a DEV copy from the test step
        # next to the REL jars made the first publishing run of Build 55 fail with "expected exactly one".
        self.assertIn("RELEASE_CHANNEL: ${{ steps.relnum.outputs.channel }}", stage)
        for exact in ('binary="$libs/sourbyapi-26.2-$RELEASE_CHANNEL.jar"',
                      'sources="$libs/sourbyapi-26.2-$RELEASE_CHANNEL-sources.jar"',
                      'javadoc="$libs/sourbyapi-26.2-$RELEASE_CHANNEL-javadoc.jar"'):
            self.assertIn(exact, stage)
        self.assertNotIn("sourbyapi-*.jar", stage)
        self.assertNotIn("expected exactly one", stage)
        for member in ("sourbyapi-$RELEASE_NUMBER.jar", "sourbyapi-$RELEASE_NUMBER-sources.jar",
                       "sourbyapi-$RELEASE_NUMBER-javadoc.jar", "README.txt"):
            self.assertIn(member, stage)
        self.assertIn('compileOnly(files("libs/sourbyapi-$RELEASE_NUMBER.jar"))', stage)
        # The archive is verified as an archive: member list, integrity, then the jars inside it.
        self.assertIn("unzip -Z1", stage)
        self.assertIn("unzip -tq", stage)
        self.assertIn("dev/iyanz/sourbycraft/api/scheduler/AuroraScheduler.class", stage)
        # No loose copy of a jar may be written into release/ (the Docker context admits release/*.jar and the
        # release job uploads only what it names).
        self.assertNotRegex(stage, r'cp [^\n]* "release/sourbyapi-')

    def test_staging_follows_the_docker_steps_because_the_context_admits_release_jars(self):
        build = job("build")
        self.assertLess(build.index("Inspect image"), build.index("Stage the sourbyapi release asset"))

    def test_only_the_zip_rides_in_the_existing_release_artifact(self):
        build = job("build")
        handover = build[build.index("Hand the verified jar to the release job"):]
        self.assertIn("name: SourbyCraft-26.2-release", handover)
        self.assertIn("release/sourbyapi-${{ steps.relnum.outputs.number }}.zip", handover)
        self.assertNotRegex(handover, r"sourbyapi-\$\{\{[^}]*\}\}[^\n]*\.jar")

    def test_release_job_attests_the_zip_and_attaches_it_to_the_create_call(self):
        release = job("release")
        attest = release[release.index("uses: actions/attest-build-provenance"):release.index("- name: Create GitHub release")]
        self.assertIn("release/sourbyapi-${{ needs.build.outputs.number }}.zip", attest)
        create = release.index('gh release create "$tag"')
        zip_added = release.index('assets+=("$api_zip")')
        self.assertLess(zip_added, create)
        self.assertNotIn("gh release upload", release)

    def test_no_release_asset_other_than_the_server_jar_ends_in_dot_jar(self):
        # SourbyUpdater of Builds 51-54 picks the first asset ending in .jar; the release job names its assets
        # explicitly, so the only .jar it may attach is the server jar (plus its .sha256/.sig companions).
        release = job("release")
        create_block = release[release.index("assets=("):release.index('gh release create "$tag"')]
        self.assertNotRegex(create_block, r"sourbyapi[^\n]*\.jar")

    def test_the_example_plugin_is_compiled_with_the_other_opt_in_projects(self):
        build = job("build")
        self.assertRegex(build, r"-PincludeTestPlugin=true [^\n]*:legacy-test-plugin:jar :sourbyapi-example:jar")

    def test_least_privilege_is_unchanged(self):
        writes = [l for l in WORKFLOW.splitlines() if "contents: write" in l and not l.lstrip().startswith("#")]
        self.assertEqual(1, len(writes), "only the release job writes")
        self.assertIn("contents: write", job("release").split("steps:")[0])
        self.assertNotIn("sourbyapi", job("rebuild"), "the reproducibility rebuild compares the server jar only")


if __name__ == "__main__":
    unittest.main()
