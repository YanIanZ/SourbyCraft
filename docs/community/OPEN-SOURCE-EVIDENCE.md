# Open-source adoption and evidence

This page is for downstream developers, server operators, contributors and
reviewers who want a **verifiable** account of SourbyCraft. It is not a
marketing scorecard. Source availability and GitHub activity show maintenance;
they do **not** prove a large installed base.

## What can be verified publicly

| Evidence | Where to verify | What it means |
| --- | --- | --- |
| GPL-3.0-only server sources and upstream attribution | [License](../../LICENSE.md) | The public code and patch files have a defined open-source license and recorded provenance |
| Region-threading, Aurora Engine and bridge source | [Architecture](../architecture/AURORA.md) and [repository](../../sourbycraft-server/) | Design and implementation can be inspected; production safety needs separate testing |
| Versioned GitHub releases and release notes | [Releases](https://github.com/YanIanZ/SourbyCraft/releases) and [release docs](../releases/) | Artifacts and changes are published, but qualification differs by build |
| SourbyAPI binaries, sources, Javadocs (Build 55+) | [Plugin developer guide](../guides/developing-with-sourbyapi.md) | Independent plugin authors can compile against release assets |
| Example plugin | [Example](../../examples/sourbyapi-example/) | Documents how to integrate; it is **not** evidence of external adoption |
| Pull requests and CI history | [Pull requests](https://github.com/YanIanZ/SourbyCraft/pulls) and [Actions](https://github.com/YanIanZ/SourbyCraft/actions) | Public engineering and automation history, subject to the limits documented in AGENTS.md |

As of the initial review on **2026-11-06**, independent downstream
dependency counts, package-registry download totals and a broad base of
external contributors have **not been verified** for this repository.
Unknown counts must not be silently replaced by zero or inflated values.

## Share a downstream use case (voluntary)

If you are actively using a public SourbyCraft build or compiling a
SourbyAPI-based plugin, a concise public issue or discussion may include:

- the name and public URL of your open project **if you want it cited**;
- which SourbyCraft release or API artifact you tested;
- the specific API, behavior or technical limitation relevant to your use;
- an anonymized compatibility report or reproducible benchmark if available;
- whether the maintainers may quote or link to your report.

Do not disclose private server names, addresses, player details, business
metrics or telemetry unless you choose to make them public and are entitled
to do so. A self-authored example, internal test server or synthetic
benchmark is **not** an independent downstream dependent.

## Evidence needed before making stronger claims

- **Adoption:** independent downstream repositories, public plugins using
  SourbyAPI, public package usage or opt-in operator reports.
- **Community:** merged contributions from independent maintainers and
  reproducible issue-resolution history.
- **Performance:** controlled before/after workload data with source
  revisions and hardware details; see [BASELINE.md](../BASELINE.md).
- **Security/stability:** tests, qualified owner-run scenarios and tracked
  release gates; see [DEVELOPMENT.md](../DEVELOPMENT.md).
- **Build reproducibility:** a public-toolchain path or alternative that
  works from a fresh checkout. At present the full-server build needs
  [private pinned tools](../development/PRIVATE-TOOLCHAIN.md).

When evidence is available, update this page with the source URL and
observation date. Avoid unverifiable claims about user count,
performance, security or dependency footprint.
