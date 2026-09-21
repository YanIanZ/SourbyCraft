# Slim bootstrap incident — fixed in SourbyClip 3.0.26

Found 2026-09-20 while trying to verify patch 0018. Recorded because it affects releases, not
just measurement, and because it contradicts a claim made earlier the same day.

## Resolution — 2026-09-21

The private implementation is fixed and pinned at SourbyClip `3.0.26`, revision
`5b46c3b5a7033cf53ef2b833a60c83190c286b56`. The historical diagnosis below identified the
HTTP-200 mirror problem, but fresh-process qualification exposed two additional causes:

1. Library SHA-256 checks shared one mutable `MessageDigest` across download workers,
   intermittently rejecting intact embedded JARs. Hash state is now owned per operation,
   and file hashing streams through a bounded buffer.
2. The fourth `libraries.list` coordinate component is a native classifier, not packaging.
   Bootstrap downloads now parse `group:artifact:version[:classifier]` as JAR coordinates.
3. A hash mismatch is now a repository miss, and each attempt is staged in a unique temporary
   file. Only verified bytes replace a library destination; failed attempts preserve old files.

Ten private bootstrap regression tests pass, including an eight-worker hashing test,
classified-native download, HTTP-200 HTML fallback, offline cache checks and failed-write cleanup.

Local end-to-end evidence (`scripts/verify_bootstrap.py`, no copied caches):

| Phase | Time to Done | Exit |
|---|---:|---:|
| Empty-cache first boot | 25.1 s | 0 |
| Cached restart with `-Dsourbyclip.offline=true` | 7.0 s | 0 |

Tested server JAR: `58b701526caf273d49b22b3894d630a79569e47e37e4932da742c09dc31a59fd`
(SHA-256), build 46 DEV with SourbyClip 3.0.26. Separate logs, JSON and JFR files were recorded
under `build/bootstrap-3.0.26-20260921/`. This is bootstrap qualification, not performance or soak
qualification. The reconstructed patch below is historical; the private source is authoritative.

## Symptom

Booting `build/libs/SourbyCraft-slim.jar` in an empty directory — no `libraries/`, no `cache/` —
fails during bootstrap:

```text
RuntimeException: All maven repo download attempts has been failed for
  library com.github.luben:zstd-jni:1.5.7-15!
  Suppressed: java.io.IOException: Downloaded library SHA-256 mismatch: com.github.luben:zstd-jni:1.5.7-15
```

Which library it names varies with ordering. Every one named so far is on
`externalizeArtifactDirs` in `build.gradle.kts` — the list `slimServerJar` **strips** from the
jar so SourbyClip re-downloads them by coordinate.

## What is and is not consistent

The jars themselves are fine. For every library checked, the hash in `META-INF/libraries.list`
equals the hash of the copy bundled in the fat paperclip jar. The failure is on the *download*
path: what Maven serves for that coordinate hashes differently from what `libraries.list`
records.

So the shape is:

- **bundled** library → hash matches → boots,
- **stripped** library → must be downloaded → download hash ≠ recorded hash → refused → boot dies.

A server with a populated `libraries/` from an earlier boot keeps working, because
`DownloadContext` returns early when the file exists and its hash validates. That is why the
deployment server is unaffected and why every measurement run that passed `--cache-from` passed.

## The claim this corrects

T8's gate item *"cached runtime boots offline"* was recorded as met by reading
`DownloadContext` and confirming it skips a valid cached file. That reasoning was right about
the cached path and said nothing about the uncached one — and the uncached path is what a new
deployment runs. **Reading the code verified the wrong half.** The item should read: a
*populated* runtime boots offline; a *fresh* one does not boot at all.

## What this blocks

- fresh installs of the slim jar,
- any baseline run in a new directory without `--cache-from`,
- and, transitively, verification of patches 0017 and 0018, which is how it was found.

## Root cause

`repo.menthamc.org` answers **HTTP 200 with an HTML body** for artifacts it does not have.

Probing the four repositories SourbyClip is built with, for
`dev/iyanz/sourbycraft/sourbyapi/26.2-DEV/sourbyapi-26.2-DEV.jar`:

| Repository | Status | Body |
|---|---|---|
| `maven.aliyun.com/repository/central` | 404 | 689 B |
| `repo.papermc.io/repository/maven-public` | 404 | 170 B |
| **`repo.menthamc.org/repository/maven-public`** | **200** | **995 B** |
| `repo.spongepowered.org/maven` | 404 | 1602 B |

`sourbyapi` is SourbyCraft's own artifact and is published nowhere, so *every* one of those
responses is a miss. Three say so. The fourth returns 200, and a downloader that treats 200 as
success writes that HTML into `libraries/` under a `.jar` name.

The hash check then fails — `Downloaded library SHA-256 mismatch` — and because no repository
after it is consulted, the boot dies.

**Correction to an earlier version of this document.** It claimed the bad file stays on disk and
poisons later boots. That is wrong. `Downloader.deleteIfInvalid()` is misleadingly named: it
creates the output directory if absent and then calls `Files.deleteIfExists(outputFile)`
unconditionally, with no validity check. Every attempt starts from a clean slate, so the failure
is deterministic rather than cumulative — each boot re-fetches and re-fails. The claim was made
from the error text rather than from the bytecode, before the bytecode was read.

The artifacts themselves were verified good. All 58 stripped libraries return 200 from at least
one repository, and `zstd-jni` hashes identically from Maven Central, aliyun and papermc, all
three matching `libraries.list`.

## Where the fix belongs

In SourbyClip, which is a separate private repository pinned by
`build-data/private-toolchain.lock.json` and therefore not fixable from this tree. Any one of
these closes it:

- **verify before persisting** — write to a temporary file, hash it, and move it into place
  only when it matches. Both paths currently write straight to the final path and validate
  afterwards, so a crash or a concurrent reader between those two steps sees a corrupt jar,
- **reject non-archive responses** — a 200 whose body is HTML, or which is orders of magnitude
  smaller than expected, is a miss regardless of status code,
- **keep trying** — a hash mismatch from one repository should fall through to the next rather
  than ending the attempt,
- drop `repo.menthamc.org`, or move it last.

A reconstructed patch for those first two points is in
[`sourbyclip-download-fix.patch`](sourbyclip-download-fix.patch). It is written against bytecode
rather than sources, since SourbyClip is private, so it is applied by hand rather than with
`git am`.

## Historical workaround before 3.0.26

Ship with `libraries/` pre-populated, or boot once somewhere the downloads succeed and copy the
directory. A populated runtime is unaffected: `DownloadContext` returns early when the file
exists and its hash validates, which is why the deployment server and every `--cache-from` run
kept working while fresh installs did not.
