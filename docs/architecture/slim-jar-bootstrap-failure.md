# The slim jar does not boot on a clean machine

Found 2026-09-20 while trying to verify patch 0018. Recorded because it affects releases, not
just measurement, and because it contradicts a claim made earlier the same day.

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

Two consequences follow, and the second is what makes it look like a build problem:

1. the hash check then fails — `Downloaded library SHA-256 mismatch`,
2. **the bad file stays on disk**, so the next boot finds it, fails its hash, and reports
   `Bundled library SHA-256 mismatch` — which reads like the jar is corrupt when the jar is
   fine.

The artifacts themselves were verified good. All 58 stripped libraries return 200 from at least
one repository, and `zstd-jni` hashes identically from Maven Central, aliyun and papermc, all
three matching `libraries.list`.

## Where the fix belongs

In SourbyClip, which is a separate private repository pinned by
`build-data/private-toolchain.lock.json` and therefore not fixable from this tree. Any one of
these closes it:

- **verify before persisting** — hash the response body and only then write it into
  `libraries/`, so a bad answer cannot poison later boots. This is the important one: without
  it, a single bad response breaks an installation permanently,
- **reject non-archive responses** — a 200 whose body is HTML, or which is orders of magnitude
  smaller than expected, is a miss regardless of status code,
- **keep trying** — a hash mismatch from one repository should fall through to the next rather
  than ending the attempt,
- drop `repo.menthamc.org`, or move it last.

## Workaround until then

Ship with `libraries/` pre-populated, or boot once somewhere the downloads succeed and copy the
directory. A populated runtime is unaffected: `DownloadContext` returns early when the file
exists and its hash validates, which is why the deployment server and every `--cache-from` run
kept working while fresh installs did not.
