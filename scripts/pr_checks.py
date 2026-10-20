#!/usr/bin/env python3
"""Run the scripts/test_*.py suite in the pull-request job, which has no private toolchain.

Pull requests get no secrets (owner decision 2026-10-20), so the PR job cannot publish
SourbyPatcher/SourbyClip to Maven Local, cannot configure Gradle (settings.gradle.kts refuses without
the pinned SourbyPatcher jar) and therefore has no materialized sources and no generated
sourbycraft-server/build.gradle.kts. The few tests below read exactly those inputs; here they are
reported as skipped with that reason instead of failing. Everything else runs unchanged, and the
full suite, these tests included, still runs in the trusted push/dispatch build job.

A listed test that no longer exists fails the run, so the list cannot silently go stale.
"""
import sys
import unittest
from pathlib import Path

SCRIPTS = Path(__file__).resolve().parent

# test id (module.Class or module.Class.method) -> the private-toolchain input it needs
NEEDS_PRIVATE_TOOLCHAIN = {
    "test_clip_launcher.ClipLauncherTest":
        "the pinned SourbyClip jar in Maven Local (private_toolchain.py --publish)",
    "test_plugin_provisioning.PluginProvisioningTest.test_hook_precedes_the_actual_plugin_scan":
        "materialized Minecraft sources (applyAllPatches)",
    "test_toolchain_policy.DeclaredTargetTest.test_the_policy_actually_found_the_build_scripts":
        "the generated sourbycraft-server/build.gradle.kts (applyAllPatches)",
    "test_toolchain_policy.DeclaredTargetTest.test_the_shipped_modules_are_on_the_baseline":
        "the generated sourbycraft-server/build.gradle.kts (applyAllPatches)",
}


def iterate(suite):
    for item in suite:
        if isinstance(item, unittest.TestSuite):
            yield from iterate(item)
        else:
            yield item


def class_id(test):
    return f"{type(test).__module__}.{type(test).__qualname__}"


def filtered_suite(suite, needs=NEEDS_PRIVATE_TOOLCHAIN):
    """Replace every listed test (or every test of a listed class) by a skip; return (suite, missing ids)."""
    found = set()
    result = unittest.TestSuite()
    for test in iterate(suite):
        reason = None
        for key in (test.id(), class_id(test)):
            if key in needs:
                found.add(key)
                reason = needs[key]
        if reason is None:
            result.addTest(test)
        else:
            def skipped(self, _reason=reason):
                raise unittest.SkipTest("needs the private toolchain: " + _reason)
            result.addTest(unittest.FunctionTestCase(skipped.__get__(test), description=test.id()))
    return result, sorted(set(needs) - found)


def main():
    loader = unittest.TestLoader()
    suite, missing = filtered_suite(loader.discover(str(SCRIPTS), pattern="test_*.py"))
    if missing:
        print("pr_checks: listed tests no longer exist: " + ", ".join(missing), file=sys.stderr)
        return 1
    outcome = unittest.TextTestRunner(verbosity=1).run(suite)
    print(f"pr_checks: {len(NEEDS_PRIVATE_TOOLCHAIN)} entries skipped for lack of the private toolchain; "
          "they run in the push/dispatch build job.")
    return 0 if outcome.wasSuccessful() else 1


if __name__ == "__main__":
    sys.exit(main())
