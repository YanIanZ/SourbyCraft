# Performance Optimization & Java 25 Support

**Date:** 2026-05-13
**Status:** Design approved

---

## §G Goal

Improve SourbyCraft runtime and build performance while ensuring compatibility with Java 21 through Java 25 at runtime. Compile target stays Java 21.

## §C Constraints

- Must compile with Java 21 toolchain (no Java 25 language features in source)
- Must run on Java 21 through Java 25 JRE
- Must not degrade existing Paper/Pufferfish patch compatibility
- Build must remain reproducible

## §I Invariants

1. `./gradlew createMojmapPaperclipJar` must succeed with zero errors
2. Server must start on Java 21, 22, 23, 24, and 25 without `--add-opens` warnings
3. Existing Pufferfish SIMD path must not regress
4. Configuration Cache must not produce stale outputs on patch changes

---

## §T Tasks

| # | Task | Area | Complexity |
|---|------|------|------------|
| T1 | Bundle `paperclip.conf` with ZGC + CDS + add-opens flags | JVM | Low |
| T2 | Fix `SIMDChecker` Java 22+ runtime detection | Patches | Low |
| T3 | Replace `LongOpenHashSet` allocation in entity visibility with pooled collection | Patches | Medium |
| T4 | Audit spawn/heightmap array paths for boxing | Patches | Low |
| T5 | Enable Gradle Configuration Cache | Build | Low |
| T6 | Enable forked compilation with 512M heap | Build | Medium |
| T7 | Split `applyAllPatches` for parallel server/minecraft/api patching | Build | Medium |
| T8 | Add `--add-exports` for JDK 25 Gradle daemon compat | Build | Low |
| T9 | Bump Gradle JVM args to `-Xmx3G` + ZGC | Build | Low |
| T10 | Verify build + server start on Java 21 through 25 | QA | Low |

---

## §V Verification

1. `./gradlew createMojmapPaperclipJar` completes with Configuration Cache enabled
2. Server starts with `java -jar sourbycraft-paperclip-*.jar --nogui` on Java 21-25
3. `SIMDDetection#isSupported()` returns `true` on CPUs with AVX2
4. Entity visibility path shows zero `new LongOpenHashSet()` allocations per tick
5. No `--add-opens` warnings in server log

---

## §B Design Decisions

1. **Compile target stays 21** — allows running on older JVMs. Java 25-specific optimizations use `jdk.incubator.vector` already present.
2. **ZGC over G1** — ZGC's sub-millisecond pauses benefit Minecraft's tick loop. Generational ZGC available since Java 21.
3. **Configuration Cache over parallel patching** — CC is lower risk and gives bigger win (15s vs 5s). Parallel patching is stretch goal.
4. **Pooled collections over full rewrite** — minimal code change, measurable allocation reduction.
