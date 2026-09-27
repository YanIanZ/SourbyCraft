# Bootstrap download audit

AURORA-TASKS I: downloader timeouts, SHA/cache validation, bounded concurrency, retry and
failure handling, first-boot recovery and offline-after-success. Audited 2026-09-27.

In scope: the two downloads in this repository that run before the server starts, `bootstrap/LibDownloader`
(libraries the slim jar omits) and `bootstrap/PluginProvisioner` (the pinned ViaVersion and ViaBackwards jars).
**Out of scope:** SourbyClip, which lives in the private `YanIanZ/SourbyClip` repository and was not
audited here. CI's "Verify packaged bootstrap download and cache behavior" step probes it from
outside.

## Findings and fixes

| Concern | Before | Now | Evidence |
| --- | --- | --- | --- |
| Connect timeout | 15 s | 15 s | `HttpClient.connectTimeout` |
| Stalled body | `HttpRequest.timeout` bounds only the response headers, so a body stalling after them could hang boot indefinitely | Whole transfer bounded by `TRANSFER_DEADLINE` (10 min): `sendAsync` + `get(deadline)`, cancelled on expiry | `LibDownloader.httpsFetch` |
| Oversized body | Written in full, then compared with the pinned size, so it could fill the disk | Cut off as soon as it passes the pinned size (`LimitedSubscriber`) | `LibDownloaderTest.theHttpsBodyIsCutOffOnceItExceedsThePinnedSize` |
| Retry | None: one transient failure meant `exit 3` | 3 attempts per library, 2 s then 4 s apart. Refusals (non-https, path escape) are not retried | `aTransientFailureIsRetriedWithBackoff`, `nonHttpsAndEscapingPathsAreRefusedWithoutRetry` |
| Partial files | A failure during the transfer left `.tmp` behind | Every attempt deletes its `.tmp`; the destination is replaced only by a verified file | `everyAttemptFailingKeepsNothingAndReportsTheLastError` |
| Hash / size | Checked before install | Unchanged; wrong bytes are never installed | `wrongBytesAreNeverInstalled` |
| Cache | Existing file used only if its SHA-256 matches | Unchanged; a corrupt cached file is re-downloaded | `aCorruptCacheIsReplaced` |
| Offline after success | Cache hit touches no network | Unchanged, now tested | `aVerifiedCacheIsReusedWithoutTheNetwork` |
| Concurrency | One download at a time, manifest order | Unchanged | `SourbyBootstrap` loop |
| Plugin jars | Same stall and size issues as above | Use the same bounded transport. Failures still never block boot (the provisioner is wrapped) | `PluginProvisioner` |

## Still open

- A first-boot failure recovery test against a real server boot (the unit tests cover the
  downloader's state after a failure, not a whole restart).
- An offline boot in CI after a successful first boot.
- SourbyClip's own downloader.
- Documentation of remote repository fallbacks: the manifest has one URL per library and no
  mirror list, so there is no fallback to document yet.
