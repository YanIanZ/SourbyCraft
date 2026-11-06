# Contributing to SourbyCraft

Thank you for helping improve SourbyCraft. The project is a **GPL-3.0-only**,
Paper-derived, region-threaded Minecraft server. Contributions must respect
upstream notices and the repository's [license](LICENSE.md) and
[development correctness policy](docs/DEVELOPMENT.md). Please do not treat a
proposed feature, a passing compile, or a GitHub release as proof of a
production-qualified result.

## Good first ways to contribute

- Improve documentation, diagnostics and reproducible bug reports.
- Add or improve public **SourbyAPI** examples, API contract tests and Javadocs.
- Investigate well-scoped compatibility issues with clear reproductions.
- Review tests and verify safe region ownership, cancellation and lifecycle behavior.
- Report measured bottlenecks *with a reproducible baseline*, rather than
  speculative performance claims.

Read the [developer path](docs/README.md),
[SourbyAPI plugin guide](docs/guides/developing-with-sourbyapi.md)
and [architecture entry point](docs/architecture/AURORA.md)
before proposing significant changes.

## Open an issue

Before opening an issue, search existing issues and the relevant release notes.
For a non-security bug, include:

1. Exact SourbyCraft release/build and Java version.
2. Relevant configuration, server OS and plugin versions (redacted).
3. Minimal reproduction, expected behavior and actual behavior.
4. Relevant **redacted** logs or stack traces, and whether the problem also
   reproduces with nonessential plugins removed.
5. Steps already tried, and whether the observation was repeated.

Never publish credentials, secrets, player IP addresses, session data or
unredacted private logs. For a suspected vulnerability use
[SECURITY.md](SECURITY.md) instead of a public issue with an exploit.

## Submit a pull request

1. Open an issue or discuss the intended scope if the behavior or contract is
   not already agreed.
2. Branch from **`26.2`** for the active 26.2 work. Keep the patch focused and
   link the relevant issue.
3. For Minecraft or Paper changes, follow the patch workflow in
   [AGENTS.md](AGENTS.md): submit persistent feature patches, not changes
   only to a materialized worktree.
4. Add tests and documentation for changed behavior. State what you actually
   ran, what passed, and what remains unverified. Do not claim manual testing,
   performance gains or production stability without supporting evidence.
5. Preserve existing attribution and licenses for any ported upstream code.
   Name original authors and origins as required by their licenses.
6. If AI tools helped with a contribution, personally review the changes,
   check relevant upstream behavior and take responsibility for correctness.

Maintainers may request smaller PRs or defer changes that weaken region
ownership, data integrity or observability.

## Build and testing limitation

**The full server currently cannot be configured and built from a fresh
public checkout alone.** The maintainer uses private SourbyPatcher and
SourbyClip tools with pinned artifact hashes; see
[PRIVATE-TOOLCHAIN.md](docs/development/PRIVATE-TOOLCHAIN.md).
Do **not** describe the source build as publicly reproducible.

What an external contributor **can** do without that toolchain:

- Read, document and review the public code and patch files.
- Compile plugin examples against the public SourbyAPI JAR from a
  [Build 55 or later release](docs/guides/developing-with-sourbyapi.md).
- Run standalone checks that have no private-toolchain dependency, where
  installed (for example, relevant `python3 -m unittest` tests in `scripts/`).
- Propose a PR with a clear note that full Gradle build/boot tests were
  **not run**.

On pull requests, GitHub Actions runs read-only static/policy checks but
**does not run the Java build, server integration tests or boot test**.
The protected maintainer toolchain is used for the push-time CI jobs.
The project owner is responsible for the specified manual server tests.
See [AGENTS.md](AGENTS.md) for the current CI stages and commands.

## Evidence and maintenance

For benchmarks, include JVM arguments, CPU/RAM, baseline and candidate
commit IDs, configuration, workload, duration and full measurement data.
Use [BASELINE.md](docs/BASELINE.md); a faster microbenchmark alone does not
establish a safe production improvement.

If SourbyCraft or SourbyAPI is used in another public project, you can
voluntarily share a public link and your permission to cite it. There is
**no requirement** to disclose a private server, player details or usage
metrics. See [open-source evidence](docs/community/OPEN-SOURCE-EVIDENCE.md).
