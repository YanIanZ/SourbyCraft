# NMS-compat matrix r1 — 2026-06-04

Single-jar (mojmap) shipping per 2026-06-04 spec revision. Reobf jar dropped:
paperweight 2.0 deprecates reobf builds and the bypass produces a jar that crashes
at boot with MCTypeRegistry initializer error.

| Variant | Plugin             | Enabled | Sanity  | Fail reason                              | Stack    |
| ------- | ------------------ | ------- | ------- | ---------------------------------------- | -------- |
| mojmap | Citizens | no | no | NOT_LOADED |  |
| mojmap | NBTAPI | no | no | NOT_LOADED |  |
| mojmap | DecentHolograms | no | no | NOT_LOADED |  |
| mojmap | FastAsyncWorldEdit | no | no | NOT_LOADED |  |

## Legend

- **Enabled**: `Bukkit.getPluginManager().getPlugin(name)` non-null + `isEnabled() == true`.
- **Sanity**: per-plugin fixture executed without exception. See
  `test-harness/sanity-harness-plugin/src/main/java/dev/iyanz/sourbycraft/nms/SanityFixtures.java`.
- **Fail reason**: exception class + first 40 chars of message. Truncated; full trace in boot.log.
- **Stack**: first 8 hex chars of sha1(normalized stack trace). Stable across runs for the same bug.

## Plugin sources

Pin versions in `test-harness/test-plugins/manifest.yml`. Latest fetch timestamp:
`Jun  4 01:16:09 2026`

## Investigation notes

(populate per row during Phase 3 fixes)
