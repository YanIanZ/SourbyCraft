# First Metal Upgrade PR — Exact Change Inventory

**Status:** in-progress code candidate targeting SourbyCraft branch `26.2`. This is a small first installment of the Build 56–100 program, **not** the whole roadmap and **not** a Build 56 release.

## Code changes in this PR

| File | Change | Effect |
|---|---|---|
| `Metal/src/main/java/.../SWMRLong2ObjectHashTable.java` | fix inverted value comparator and use logical key/value in hashCode | correct equal-map semantics; may change map hash value, version/API review needed |
| `Metal/src/test/java/.../SWMRLong2ObjectHashTableEqualityTest.java` | basic JUnit regression | test fixture for owner |
| `Metal/gen_sources.sh` | use temporary Java 25 generator class directory and absolute paths | repeatable execution from any cwd; no template `.class` leakage |
| `Metal/templates/GenerateSources.java` | skip unchanged output, atomic-rename fallback | generated Java files are less likely to truncate on interruption |
| `Metal/build.gradle.kts` | `generateMetalSources` precompile dependency | Metal compile no longer requires an external generator command |
| `scripts/setup_metal.sh` | call generator from script path | compatible with CI working directory |
| `sourbycraft-server/build.gradle.kts.patch` | `metalProvider=upstream/modular/vendored` | no default runtime change, explicit candidate selection |
| `gradle.properties` | `metalModularVersion=1.3.2` pin | deterministically identifies modular candidate |
| `.github/workflows/build.yml` | include `:Metal:test` in existing build CI test suite | runs vendored regression on push/release build; no Java tests on PR due private toolchain CI policy |
| `Metal/README.md` and `docs/metal/*` | qualification and long-range plans | no release-feature claim |

**Deliberately not changed:** `sourbyBuild`, `release`, `paperRef`, Folia scheduler implementation, AWF storage format, plugin API/UUID, network territory code, server default runtime provider.

## Not yet implemented

- Source rebase of `Metal/` to LeafPile 1.3.2; candidate `modular` uses published dependencies instead.
- Class-origin and duplicate-class automated verifier in release workflow.
- Runtime instrumentation for each Metal data structure and full stress/replay harness.
- Metal-specific hot-path replacements, scheduler modifications, allocator/NUMA experiments, territory sharding optimizations.
- Performance, functional or compatibility proof. No tests executed by the assistant.

## Admission checklist

- [ ] Owner confirms patch application and `:Metal:test` with Java 25
- [ ] Owner builds all 3 provider variants
- [ ] Owner confirms runtime class loading and absence of duplicate packages
- [ ] Owner verifies JUnit equality/hash behavior and any downstream `hashCode()` dependence
- [ ] Owner runs current SourbyCraft Build 50 and AWF/bridge/MultProfile acceptance gates
- [ ] Owner collects same-load performance comparison
- [ ] Owner decides which provider should be baseline for Build 56
- [ ] Owner approves merge and release/rollout separately

The owner may merge **infrastructure changes with default runtime unchanged** before full opt-in qualification, but must not advertise the vendored or modular provider as stable until the relevant tests pass.
