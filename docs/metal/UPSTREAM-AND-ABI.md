# Metal / LeafPile Update and ABI Policy

**Status:** upgrade policy proposal with an initial candidate implemented in the companion code PR. No automated fetch or source rebase is implemented.

## Upstream distinction (as verified 2026-11-03)

- Existing server: `ca.spottedleaf:leafpile:1.2.0` monolith in `sourbycraft-server/build.gradle.kts.patch`.
- Metal vendored: old monolithic source under `Metal/`, own Java 25 generator.
- Latest **verified released** candidate for this plan: [LeafPile v1.3.2](https://github.com/Tuinity/LeafPile/releases/tag/v1.3.2), with modules in [settings.gradle.kts](https://github.com/Tuinity/LeafPile/blob/v1.3.2/settings.gradle.kts). Upstream master indicates `1.3.3-SNAPSHOT`; SNAPSHOT is **not** assumed released.
- Paper mainline uses BOM `ca.spottedleaf.leafpile:bom:1.3.2` plus common/concurrentutil/converter/ioutil; SourbyCraft's pinned Paper revision may have different class and dependency shape.

## Policy for every planned update

1. **Discover** the latest *released* tag using GitHub release/tag evidence, not the version at upstream `master`.
2. **Record** tag, commit SHA, publisher, release date, changed module list, license/NOTICE and source archive digest.
3. **Review** changelog/commits for races, memory ordering, task cancellation, queue behavior, public class removals, record layout, JVM baseline, performance, API binary breaks and serialization format.
4. **Diff** the vendored `Metal/` classes against that tag. Track upstream changes separately from SourbyCraft-specific deviations; retain copyright/attribution.
5. **Qualify** module-provider canary first, then vendored source as a distinct candidate. A successful modular build does *not* automatically qualify vendored code.
6. **Verify dependency tree and class origins**: Paper transitive deps, standalone Metal JAR, shaded fat JAR and `ca.spottedleaf.*` package ownership. Detect duplicates and inadvertent older class loading.
7. **Run owner cases** at the candidate SHA: `:Metal:test`, all server tests, real plugin boots, region stress, AWF save/recovery and paired benchmarks; document failures.
8. **Promote** to default only after owner sign-off, and retain explicit fallback with old qualified JAR for the first rollout.

## Release tracking ledger (update with each candidate)

| Sourby build range | Upstream reference | Metal provider | Evidence state | Outcome |
|---|---|---|---|---|
| Current 26.2 Build 50 | monolith 1.2.0 | default `upstream` | documented current baseline, not full qualified Build 50 | preserve |
| Candidate PR ahead of 56 | LeafPile v1.3.2 | opt-in `modular` | **unbuilt, untested** | experimental |
| Candidate PR ahead of 56 | vendored older source + correctness fixes | opt-in `vendored` | **unbuilt, untested** | experimental |
| Builds 56–100 | new releases, only when verified | owner-selected | open ledger | no prediction |

Do not assert that a LeafPile version is the latest indefinitely. Check upstream immediately before a later candidate PR.

## Compatibility axes

- **Source API:** imported package/class exists and signature matches.
- **Binary ABI:** bytecode linkage, superclass/interfaces, generic erasure, exposed field/method descriptors.
- **Algorithm semantics:** queue cancellation, iteration concurrency, SWMR write ownership, compare-and-set transitions.
- **Build:** JDK 25, Gradle, private SourbyPatcher, patched Paper and code-generated files.
- **Runtime:** class load origin, service loader/manifest, Paper modules and plugins, no duplicate class names.
- **Persistence:** no hidden serialization-compat change or world snapshot corruption.
- **Performance:** apples-to-apples workload, warmup, same JDK/hardware, repeatability, variance, worst region tails.

## Backport rules

High-severity concurrency correctness fixes may be cherry-picked with exact provenance, a minimal regression test/owner reproduction and *no silent semantic expansion*. Do not increase default thread concurrency or adopt a new scheduler in a hotfix. Performance features need explicit A/B evidence, operator opt-out and rollback.

No dependency self-update at boot; build artifacts are immutable. CI may monitor upstream versions and open proposals, but new provider versions require review and owner acceptance before production.
