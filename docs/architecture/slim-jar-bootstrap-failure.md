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

## Not yet established

Why the published artifact differs from the bundled one. Candidates, none confirmed: the build
repackaging dependencies so bundled bytes stop matching Maven's; `libraries.list` recording a
hash from a different resolution than the one published under that coordinate; or the pinned
SourbyClip revision changing how it verifies. The fat paperclip jar available locally when this
was found was `26.2-REL` while the slim jar was `26.2-DEV`, so the comparison above was made
across builds and should be repeated within one before a cause is asserted.
