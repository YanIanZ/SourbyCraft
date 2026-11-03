# SourbyCraft Metal (vendored concurrency utilities)

**Status:** experimental vendored runtime option; default production path unchanged. This module is a Java concurrency/data-structure library, **not** Apple Metal graphics/GPU compute.

## Provenance and current baseline

The repository vendors an older, monolithic snapshot related to LuminolMC/Metal and LeafPile. Its namespaces include `ca.spottedleaf.concurrentutil`, `ca.spottedleaf.common`, `ca.spottedleaf.ioutil` and other utility packages. The tracked generator produces 16 specialized concurrent hash-table source files.

SourbyCraft server's existing `ca.spottedleaf:leafpile:1.2.0` dependency remains the **default**. Current upstream [Tuinity/LeafPile](https://github.com/Tuinity/LeafPile) uses independently published modules. This PR adds a **candidate build-time** `-PmetalProvider=modular` using LeafPile BOM **1.3.2** (version pinned by `metalModularVersion` in root `gradle.properties`) plus the modules needed to replace the old monolith; it is not yet production-qualified. A source-level rebase of the vendored `Metal/` tree to modern LeafPile is a separate change, not accomplished here.

## What this upgrade changes

- Corrects `SWMRLong2ObjectHashTable.equals` **and `hashCode`**: different map instances with equal logical entries compare equal and have matching hashes; unequal entries compare unequal. Upstream v1.3.2 still hashes table-entry object identities, so this is an explicit SourbyCraft source-owned correctness delta requiring review.
- Adds JUnit 6 regression cases for negative keys, same/different values, symmetry and equal-hash contract; existing post-merge build CI includes `:Metal:test`.
- Compiles the code generator into a temporary directory with Java 25, leaving `templates/` free of generated `.class` files.
- Generates sources using absolute script-root paths, not the invoking shell's working directory.
- Only replaces generated Java source when contents differ, and attempts an atomic same-directory move to avoid incomplete output after interruption.
- Registers the generator as `:Metal:generateMetalSources`, a dependency of `:Metal:compileJava`.
- Adds **opt-in** `-PmetalProvider=vendored` to select `project(":Metal")`, or `-PmetalProvider=modular` to use the published **LeafPile 1.3.2 modular BOM**. Without this property, the existing production `leafpile:1.2.0` runtime dependency remains unchanged.

## Long-term Aurora Metal performance program

This PR is **the foundation**, not the full upgrade. See [Aurora Metal Program](../docs/metal/README.md), [Build 56–100 roadmap](../docs/metal/BUILD-56-100-ROADMAP.md), [Upstream and ABI policy](../docs/metal/UPSTREAM-AND-ABI.md), and [owner qualification](../docs/metal/PERFORMANCE-QUALIFICATION.md). Every future version update needs provenance, owner tests and benchmark evidence; this branch does not modify the released Build 50 identity.

## Commands for owner verification

Run only after the normal private SourbyPatcher/SourbyClip setup is complete. The owner performs all tests and server boots.

```bash
# 1. Generate 16 map specializations; repeat and verify no changed source.
bash scripts/setup_metal.sh

# 2. Compile and run the dedicated vendored-library regression tests.
./gradlew :Metal:compileJava :Metal:test --no-daemon

# 3. Apply source patches and compare the three build providers.
./gradlew applyAllPatches --no-daemon
./gradlew -PmetalProvider=upstream :sourbycraft-server:compileJava --no-daemon
./gradlew -PmetalProvider=vendored :sourbycraft-server:compileJava --no-daemon
./gradlew -PmetalProvider=modular :sourbycraft-server:compileJava --no-daemon

# 4. Check runtime classpath; exactly one provider should supply each utility class.
./gradlew -PmetalProvider=upstream :sourbycraft-server:dependencies --configuration runtimeClasspath
./gradlew -PmetalProvider=vendored :sourbycraft-server:dependencies --configuration runtimeClasspath
./gradlew -PmetalProvider=modular :sourbycraft-server:dependencies --configuration runtimeClasspath

# 5. Build opted-in candidates if compilation and classpath verification succeeded.
./gradlew -PmetalProvider=vendored slimServerJar --no-daemon
./gradlew -PmetalProvider=modular slimServerJar --no-daemon
```

Never place an upstream LeafPile jar and a vendored Metal jar containing the same classes on the same classpath. Check transitive dependencies and shaded/assembled artifacts before switching providers; successful Java compilation by itself does not prove packaging or class-loading correctness.

## Owner acceptance gates

1. Codegen from repository root and any other current working directory; JDK 25 mandatory, repeat generation leaves stable hashes and file timestamps where unchanged.
2. All 16 generated specialized tables compile and all Metal regression tests pass.
3. `-PmetalProvider=upstream` preserves the existing server classpath and behavior.
4. `-PmetalProvider=vendored` compiles, builds, boots, and loads `ca.spottedleaf.concurrentutil` from the intended provider, with **no duplicate class definitions**.
5. Multiple concurrent readers/one writer load tests cover SWMR contracts; no mutation is permitted off Folia ownership boundaries solely because an underlying map is thread-safe.
6. Region merge/split, chunk load/unload, async pathfinding, player handoff and current AWF/MultiProfile owner tests do not regress.
7. Evaluate same-workload TPS/MSPT, allocation, GC and `ConcurrentChainedLong2ReferenceHashTable.getNode` CPU samples; do not claim a performance gain without a controlled baseline.

**No build, owner tests, benchmarks or production certification have been performed by this documentation.**

## Rollback

Leave `metalProvider` absent or explicitly use `-PmetalProvider=upstream` to restore the existing dependency; no player world/profile data schema is changed by this PR. For a failed vendored canary, stop the server, restore the previous qualified server jar, and use the normal SourbyCraft rollback procedure. The Gradle provider property is a **build-time switch**; it is not a runtime `aurora.yml` key.

## Upgrade backlog

- Compare vendored classes with LeafPile 1.3.2 modular source and maintain a provenance/diff ledger, including behavior/ABI changes and GPL-3.0 attribution.
- Assess whether to replace all vendored code with selected versioned modules or continue a source-owned fork; never silently mix overlapping package classes.
- Verify generator reproducibility, JUnit/concurrency stress fixtures and shaded-jar service/resource metadata.
- Qualify production provider change only at an owner-approved exact build SHA, after the current Build 50 readiness gate.
