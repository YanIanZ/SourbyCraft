"""Pins the hotfix publication path of .github/workflows/build.yml (no network, no Gradle).

A hotfix is published from a branch hotfix/<mc>-r<N>.<M> cut from the tag v<mc>-r<N>; it gets the
tag v<mc>-r<N>.<M> through the same build, boot, rebuild-compare, sign and attest jobs as a 26.2
release. The release identity comes from scripts/release_version.py --hotfix-branch, which refuses
an existing tag, a missing base tag or a sourbyBuild that is not N.M. The 26.2 path is unchanged.
"""
import re
import unittest
from pathlib import Path

from test_reproducible_build import WORKFLOW, job


def relnum_step():
    build = job("build")
    start = build.index("id: relnum")
    return build[start:build.index("- name:", start)]


class HotfixWorkflowTest(unittest.TestCase):
    def test_pushes_to_hotfix_branches_run_the_workflow(self):
        push = WORKFLOW.split("pull_request:")[0]
        self.assertRegex(push, r"branches: \[ '26\.2', main, 'hotfix/26\.2-r\*' \]")
        pull = WORKFLOW.split("pull_request:")[1].split("workflow_dispatch:")[0]
        self.assertNotIn("hotfix", pull, "pull requests keep running only the secret-free checks")

    def test_relnum_resolves_hotfix_identity_through_release_version(self):
        step = relnum_step()
        self.assertIn('refs/heads/hotfix/*) hotfix=${GITHUB_REF#refs/heads/}', step)
        self.assertRegex(step, r'elif \[ -n "\$hotfix" \] && \[ "\$GITHUB_EVENT_NAME" != \'pull_request\' \] \\\n'
                               r'\s+&& \[ "\$mode" != false \] && python3 scripts/release_version\.py --hotfix-branch "\$hotfix"')
        self.assertIn('python3 scripts/release_version.py --development --hotfix-branch "$hotfix"', step)

    def test_the_26_2_release_path_is_unchanged(self):
        step = relnum_step()
        self.assertIn("if [ \"$GITHUB_REF\" = 'refs/heads/26.2' ] && [ \"$GITHUB_EVENT_NAME\" != 'pull_request' ] \\\n"
                      "              && [ \"$mode\" != false ] && python3 scripts/release_version.py > \"$RUNNER_TEMP/relnum.out\"",
                      step)
        self.assertEqual(2, step.count("publish=true"), "one for 26.2, one for a hotfix branch")

    def test_build_exports_the_base_tag(self):
        self.assertIn("base: ${{ steps.relnum.outputs.base }}", job("build"))

    def test_release_job_publishes_from_26_2_or_a_hotfix_branch_only(self):
        release = job("release")
        condition = re.search(r"\n    if: (.*)\n", release).group(1)
        self.assertIn("needs.build.outputs.publish == 'true'", condition)
        self.assertIn("success()", condition)
        self.assertIn("(github.ref == 'refs/heads/26.2' || startsWith(github.ref, 'refs/heads/hotfix/26.2-r'))", condition)
        self.assertRegex(release, r"needs: \[build, rebuild\]")

    def test_hotfix_notes_list_commits_since_the_base_tag(self):
        release = job("release")
        self.assertIn("RELEASE_BASE: ${{ needs.build.outputs.base }}", release)
        self.assertIn('since="${RELEASE_BASE:-${last:+v26.2-r$last}}"', release)
        self.assertIn('git log "$since"..HEAD', release)
        self.assertIn("Auto-published from \\`$GITHUB_REF_NAME\\`", release)

    def test_a_hotfix_of_an_older_build_is_not_marked_latest(self):
        release = job("release")
        self.assertIn('"${RELEASE_NUMBER%%.*}" -lt "${last:-0}"', release)
        self.assertIn('publish_flags="--latest=false"', release)


if __name__ == "__main__":
    unittest.main()
