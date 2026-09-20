## sourbypatcher

SourbyCraft's fork of [`paperweight`](https://github.com/PaperMC/paperweight), the Gradle
plugin PaperMC uses to build Paper and its downstream forks.

It is **not** on the path that builds SourbyCraft today. Keep reading before using it.

### Status in this repository

SourbyCraft builds through Canvas's own toolchain, `io.canvasmc.weaver.patcher`, declared in
the root `build.gradle.kts`. That was a deliberate choice ("Path B"): Canvas is a three-stage
fork whose weaver sequences access transformers and base patches in an order this fork does not
reproduce, and making sourbypatcher consume Canvas hit git-am conflicts in `TickThread.java`
and `CraftServer.java`.

So sourbypatcher is:

- **not included in any Gradle build** — the root `settings.gradle.kts` does not include it,
  and nothing resolves the `dev.iyanz.sourbypatcher` artifact,
- **not built by CI** — the workflow step that published it to Maven Local was removed once it
  was established that nothing consumed the artifact,
- **kept, and kept working** — it is the fallback if the weaver toolchain stops being viable,
  and it assembles cleanly against the same Gradle 9.4.1 and Java 25 the main build uses.

It is classified `LEGACY` / `REMOVABLE` in
[`docs/architecture/dependency-ledger.md`](../docs/architecture/dependency-ledger.md). Retiring
it is a maintainer decision, not a mechanical one.

### Modules

| Module | Notes |
|---|---|
| `sourbypatcher-core` | Builds a Paper-like server fork |
| `sourbypatcher-userdev` | Develops internals plugins against Mojang mappings |
| `paperweight-lib` | Shared library. **Keeps the upstream name** |

The mixed naming is intentional. Every Kotlin package here is still `io.papermc.paperweight`,
because this is a fork rather than a rewrite and keeping the package coordinates makes upstream
changes reviewable. Renaming `paperweight-lib` would change a directory without changing a
single package, so the name records the lineage instead of hiding it.

### Building

Assemble without running the test suite:

```bash
cd sourbypatcher && ./gradlew assemble -x test
```

Publish to Maven Local, which is how a consuming build would resolve it:

```bash
cd sourbypatcher && ./gradlew publishToMavenLocal
```

Then add `mavenLocal()` to plugin resolution in the consuming project and point its plugin
version at the `version` in `gradle.properties`. A locally published build replaces a
`-SNAPSHOT` suffix with `-LOCAL-SNAPSHOT`.

Most of what it produces lands in `<project-root>/.gradle/caches/paperweight`.

### Toolchain

Gradle **9.4.1** and Java **25**, matching the main build. Both are pinned in
`gradle/wrapper/gradle-wrapper.properties` and `buildSrc/src/main/kotlin/config-kotlin.gradle.kts`;
if the root project moves, move them together or this stops being a usable fallback.

Dependency versions live in `gradle/libs.versions.toml`.

### Debugging

Create a remote JVM debug configuration in IntelliJ on port 5005, then:

```bash
./gradlew --no-daemon -Dorg.gradle.debug=true <task>
```

Gradle waits for the debugger, so no breakpoint is missed.

### Style

`ktlint`, via the `ktlint-gradle` plugin. Run `format` to reformat, and fix anything ktlint
cannot fix itself before committing:

```bash
./gradlew ktlintApplyToIdea addKtlintFormatGitPreCommitHook
```

### Upstream and licence

Forked from PaperMC's `paperweight`, LGPL v2.1 — see [`license/`](license). Upstream copyright
and licence obligations are unchanged by the fork, and the retained `io.papermc.paperweight`
package coordinates are part of that lineage rather than an oversight.
