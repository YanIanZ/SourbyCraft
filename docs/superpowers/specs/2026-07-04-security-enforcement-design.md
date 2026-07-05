# S1 — Security Enforcement Design (sourbycraft-security.yml wiring)

**Date:** 2026-07-04
**Status:** Approved (design), pending implementation plan
**Parent effort:** Placeholder-config remediation (S1 of S1–S6). All keys in
`sourbycraft-security.yml` are currently loaded at boot
(`DedicatedServer.java:294` → `SourbyCraftSecurityConfig.init`) but have **zero
enforcement call sites**. This spec wires every existing limit to real NMS
logic and adds three adjacent packet guards.

## Goals

1. Every key in `sourbycraft-security.yml` changes observable server behavior.
2. Zero measurable overhead: all checks are primitive comparisons on data the
   server is already parsing. No extra passes, no allocation on the happy
   path, no locks, no scheduler.
3. Mixed enforcement policy (per category, chosen by operator):
   - NBT oversize → parse failure → vanilla disconnect path.
   - Sign / anvil → silent truncate (never kick a legitimate player).
   - Recipe-book / creative-item / new packet guards → drop packet + WARN.

## Non-Goals

- No generic per-packet-type rate-limit firewall, no auto-ban (deferred; would
  be its own sub-spec).
- No duplication of Paper's existing `book-size` per-page limits in
  `paper-global.yml` — the new book guard is a total-size cap layered on top.
- No JUnit tests (project convention: verify via real TestServer boot).

## Architecture

**Approach chosen:** direct static reads at NMS call sites (same pattern as
existing SourbyCraft NMS patches reading `SourbyCraftConfig` statics /
`ymlBool`). Rejected alternatives: central Netty interceptor (heavy,
duplicates vanilla decode, fragile across MC updates); Knob registry reuse
(atomic read overhead, wrong domain — knobs are perf, this is security).

### New component: `dev.iyanz.sourbycraft.security.SecurityGuard`

Static utility, no state beyond violation counters and per-category
rate-limit timestamps.

```java
public final class SecurityGuard {
    /** Rate-limited WARN (max 1 per category per second, System.nanoTime based).
     *  Aggregates suppressed count into the next emitted line. */
    public static void violation(String category, @Nullable ServerPlayer player, String detail);

    /** Total violations per category since boot (for /sys or logs). */
    public static Map<String, Long> counters();
}
```

- Happy path (no violation): never called → zero cost.
- Violation path: one `ConcurrentHashMap` lookup + nanoTime compare; string
  building only when the line is actually emitted.

### Config: `SourbyCraftSecurityConfig` changes

- New section `packet-guard:` with fields:
  - `bookEditMaxTotalChars` (default `65_536`) — total chars across all pages.
  - `customPayloadMaxBytes` (default `8_192`) — serverbound plugin-channel payload cap
    (vanilla hard cap is 32767; this is a tighter configurable cap).
  - `containerClickMaxPerTick` (default `20`) — per-player serverbound
    container-click packets per tick.
  - `recipeBookMaxPerTick` (default `16`) — per-player serverbound
    recipe-book-family packets per tick.
- Bug fix: `saveDefault()` is currently dead code. Call it from `init()` when
  the file does not exist, so fresh installs materialize the full file
  including the new section. Existing files keep their values (loader already
  tolerates missing keys → defaults).
- All new fields follow the existing pattern: `public static` primitives, read
  directly from NMS.

## Enforcement wiring map

### 1. NBT limits → `net.minecraft.nbt.NbtAccounter` (+ tag readers)

| Key | Site | Change |
|---|---|---|
| `nbt.max-bytes` | `NbtAccounter` default-quota creation sites (hardcoded `2097152` / `DEFAULT_NBT_QUOTA` usages on the network read path) | quota = `SourbyCraftSecurityConfig.nbtMaxBytes` |
| `nbt.max-depth` | `NbtAccounter` depth push (vanilla max 512) | cap = `min(512, nbtMaxDepth)` |
| `nbt.max-string-length` | string tag read through the accounter | length check before/while accounting; exceed → `accountBytes`-style failure |
| `nbt.max-list-size` | list/compound element read loop | element-count check; exceed → same failure path |

Violation → the existing vanilla exception path (malformed-packet disconnect).
This is the correct fail mode: an NBT payload over quota is by definition
hostile or corrupt. `SecurityGuard.violation("nbt", player, …)` is emitted
from the packet handler's catch site where the player context is available.

Guard rails: values are clamped at load time to sane floors
(`max-bytes ≥ 65536`, `max-depth ≥ 16`, `max-string-length ≥ 256`,
`max-list-size ≥ 256`) so a typo in the yml cannot brick normal gameplay
(shulker boxes, banners, books all fit comfortably above these floors).

### 2. Sign limits → `ServerGamePacketListenerImpl.updateSignBlock`

- Per line: `line.substring(0, min(len, signMaxLineLength))`.
- Total: running sum across the 4 lines; once `signMaxTotalChars` is
  exhausted remaining lines become empty.
- Silent truncate; no kick, no log spam (single `SecurityGuard.violation`
  only when truncation actually occurred).

### 3. Anvil limit → `AnvilMenu`

- Replace both hardcoded `50` sites (`MAX_NAME_LENGTH` constant usage and the
  `filteredName.length() <= 50` validation) with
  `SourbyCraftSecurityConfig.anvilMaxItemNameLength`.
- Note: config default (128) is **higher** than vanilla (50) — this limit is
  both a raise-cap feature and a guard. The network-level string read limit
  remains the absolute upper bound.
- Over-limit rename → treated as invalid name (vanilla `null` path, rename
  rejected) — no kick.

### 4. Recipe-book → serverbound recipe packet handlers

- Sites: `ServerboundRecipeBookSeenRecipePacket`, `ServerboundPlaceRecipePacket`,
  `ServerboundRecipeBookChangeSettingsPacket` handling in
  `ServerGamePacketListenerImpl`.
- Protocol reality: in the 26.x protocol these packets are fixed-size (int
  display ids); the historical oversized-recipe-id crash vector no longer
  exists. The live vector is packet flood.
- `recipe-book.max-packet-size` stays enforced as a decode-time guard on any
  variable-length field in the family's codecs (currently structurally
  satisfied; guards future protocol drift).
- NEW `packet-guard.recipe-book.max-per-tick` (default `16`): per-connection
  counter identical to the container-click counter; over cap → drop packet +
  `SecurityGuard.violation("recipe-book", …)`. This is the enforcement that
  bites today.

### 5. Creative item → `ServerboundSetCreativeModeSlotPacket` handler

- Measure the encoded size of the incoming item's component/NBT payload by
  serializing to a counting-only `DataOutput` sink (counts bytes, allocates
  no buffer). Creative slot packets arrive at human rates from creative-mode
  players only, so this one re-encode per packet is negligible; the happy
  path for all other packet types pays nothing.
- Over `creative-item.max-nbt-size` → drop packet (slot keeps previous
  content) + `SecurityGuard.violation("creative-item", …)`.

### 6. NEW `packet-guard.book-edit.max-total-chars` → `ServerboundEditBookPacket` handler

- Sum of page lengths (+ title length when signing) > cap → drop packet +
  violation. Layered above Paper's per-page `book-size` limits; does not
  modify them.

### 7. NEW `packet-guard.custom-payload.max-bytes` → serverbound custom payload handler

- Payload length > cap → drop + violation. Default 8192 chosen to stay above
  every mainstream plugin-messaging user (BungeeCord subchannels, WorldEdit
  CUI, floodgate handshake data) while cutting the 32 KB abuse ceiling.

### 8. NEW `packet-guard.container-click.max-per-tick` → `ServerGamePacketListenerImpl`

- Per-connection int counter, reset on tick; over cap → drop click packet +
  violation once per burst. Protects against inventory-click storm exploits.
- Counter lives on the listener instance (no map lookup).

## Error handling

- Config load failure → existing behavior (log + compiled defaults). Defaults
  are the enforcement values, so guards stay active even with a broken yml.
- All guards are branch-only code on existing parse paths; no new exception
  sources on the happy path.
- Floors/clamps (section 1) prevent operator typos from breaking gameplay.

## Verification (manual, per project convention)

1. Boot TestServer, confirm boot log line `[SourbyCraft] security limits active`
   (one INFO line emitted by `SourbyCraftSecurityConfig.init` summarizing loaded values).
2. Sign: paste > 256-char line → placed sign shows truncated text, no kick.
3. Anvil: rename 60 chars (over vanilla 50, under config 128) → rename works
   (proves the raise path); rename > 128 → rejected.
4. Creative: `/give`-style huge-NBT item via creative slot packet (test
   client or plugin) → slot unchanged, WARN in console.
5. Fresh-install check: delete `sourbycraft-security.yml`, boot → full file
   regenerated including `packet-guard:` section.
6. Regression: normal join, chest spam-clicking at human speed, book edit,
   bungee/floodgate custom-payload handshake — all unaffected.

## Files touched (expected)

- `sourbycraft-server/src/main/java/dev/iyanz/sourbycraft/security/SourbyCraftSecurityConfig.java` (new fields, clamps, saveDefault call, boot summary line)
- `sourbycraft-server/src/main/java/dev/iyanz/sourbycraft/security/SecurityGuard.java` (new)
- `patches/minecraft/*` (new patches): `NbtAccounter`, tag readers,
  `ServerGamePacketListenerImpl`, `AnvilMenu`, `ServerboundEditBookPacket`
  handler site, custom-payload handler site
- `sourbycraft-security.yml` (repo baseline: add `packet-guard:` section)

Paperweight workflow reminders: nested-git commit in
`sourbycraft-server/src/minecraft/java` before rebuild tasks; expect patch
renumbering.
