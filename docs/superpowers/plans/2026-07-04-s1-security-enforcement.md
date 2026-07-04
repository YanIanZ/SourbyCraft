# S1 Security Enforcement Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Wire every limit in `sourbycraft-security.yml` to real NMS enforcement and add a `packet-guard:` section (book-edit total cap, custom-payload cap, container-click and recipe-book per-tick rate caps).

**Architecture:** Direct static reads of `SourbyCraftSecurityConfig` fields at NMS call sites (same pattern as existing `SourbyCraftConfig` NMS reads). One new helper `SecurityGuard` provides rate-limited violation logging, counters, and a counting-only `DataOutput` for creative item size measurement. NMS edits land as three nested-git commits → three new feature patches under `patches/minecraft/`.

**Tech Stack:** Java 21+, paperweight-patcher v2 (SourbyCraft Paper 26.1.2 fork), SnakeYAML (existing loader).

**Spec:** `docs/superpowers/specs/2026-07-04-security-enforcement-design.md`

## Global Constraints

- No JUnit tests — project convention: verification via real TestServer boot by the operator (manual).
- NMS sources live in `sourbycraft-server/src/minecraft/java` (nested git repo). Every NMS change set MUST be committed on the nested git BEFORE running `./gradlew rebuildMinecraftFeaturePatches`.
- Nested git preflight: `git -C sourbycraft-server/src/minecraft/java status` must be clean and NOT mid-rebase before rebuild tasks (abort any rebase first).
- Enforcement policy (from spec): NBT → vanilla parse-failure disconnect; sign/anvil → silent truncate/reject; recipe-book/creative/book-edit/custom-payload/container-click → drop packet + rate-limited WARN.
- Zero happy-path overhead: guards are primitive comparisons on data already being parsed; no allocation, no locks, no schedulers.
- Comment markers in NMS: `// SourbyCraft start - <reason>` / `// SourbyCraft end` (match existing patches).
- Compile check command: `./gradlew :sourbycraft-server:compileJava -q` → expect `BUILD SUCCESSFUL`.

---

### Task 1: SecurityGuard helper + config extensions + baseline yml

**Files:**
- Create: `sourbycraft-server/src/main/java/dev/iyanz/sourbycraft/security/SecurityGuard.java`
- Modify: `sourbycraft-server/src/main/java/dev/iyanz/sourbycraft/security/SourbyCraftSecurityConfig.java`
- Modify: `sourbycraft-security.yml` (repo baseline)

**Interfaces:**
- Produces (used by Tasks 2–4):
  - `SecurityGuard.violation(String category, @Nullable String playerName, String detail)` — rate-limited WARN, max 1/category/second.
  - `SecurityGuard.encodedSize(net.minecraft.world.item.ItemStack stack, net.minecraft.core.HolderLookup.Provider registries)` → `long` (Long.MAX_VALUE on failure = fail-closed).
  - `SourbyCraftSecurityConfig` new public static fields: `bookEditMaxTotalChars` (int, default 65536), `customPayloadMaxBytes` (int, 8192), `containerClickMaxPerTick` (int, 20), `recipeBookMaxPerTick` (int, 16). Existing 9 fields unchanged in name/type.

- [ ] **Step 1: Create SecurityGuard.java**

```java
package dev.iyanz.sourbycraft.security;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Violation logging + counters for sourbycraft-security.yml enforcement.
 * Happy path (no violation) never calls into this class — zero cost.
 * Violation path: one map lookup + nanoTime compare; strings built only
 * when a line is actually emitted (max 1 per category per second).
 */
public final class SecurityGuard {

    private static final long LOG_INTERVAL_NANOS = 1_000_000_000L;

    private static final class Cat {
        final AtomicLong total = new AtomicLong();
        final AtomicLong suppressed = new AtomicLong();
        volatile long lastLogNanos = 0L;
    }

    private static final ConcurrentHashMap<String, Cat> CATEGORIES = new ConcurrentHashMap<>();

    private SecurityGuard() {}

    /** Records a violation; emits a rate-limited WARN (max 1/category/second). playerName may be null. */
    public static void violation(String category, String playerName, String detail) {
        Cat cat = CATEGORIES.computeIfAbsent(category, k -> new Cat());
        cat.total.incrementAndGet();
        long now = System.nanoTime();
        if (now - cat.lastLogNanos < LOG_INTERVAL_NANOS) {
            cat.suppressed.incrementAndGet();
            return;
        }
        cat.lastLogNanos = now;
        long sup = cat.suppressed.getAndSet(0L);
        dev.iyanz.sourbycraft.util.SourbyLogger.warn(
            "[security:" + category + "] " + (playerName == null ? "<unknown>" : playerName) + " — " + detail
            + (sup > 0 ? " (+" + sup + " suppressed)" : "") + " total=" + cat.total.get());
    }

    /** Total violations per category since boot (snapshot). */
    public static Map<String, Long> counters() {
        java.util.HashMap<String, Long> out = new java.util.HashMap<>();
        CATEGORIES.forEach((k, v) -> out.put(k, v.total.get()));
        return out;
    }

    /**
     * Encoded NBT size of an item stack, measured through a counting-only sink
     * (no buffer allocation). Used only on the creative-slot packet path —
     * human-rate packets, so the one serialization here is negligible.
     * Fail-closed: an unmeasurable item reports Long.MAX_VALUE (treated oversized).
     */
    public static long encodedSize(net.minecraft.world.item.ItemStack stack,
                                   net.minecraft.core.HolderLookup.Provider registries) {
        try {
            net.minecraft.nbt.Tag tag = stack.save(registries);
            CountingDataOutput sink = new CountingDataOutput();
            net.minecraft.nbt.NbtIo.write((net.minecraft.nbt.CompoundTag) tag, sink);
            return sink.count();
        } catch (Throwable t) {
            return Long.MAX_VALUE;
        }
    }

    /** DataOutput that counts bytes and discards them. */
    public static final class CountingDataOutput implements java.io.DataOutput {
        private long count = 0;
        public long count() { return count; }
        @Override public void write(int b) { count += 1; }
        @Override public void write(byte[] b) { count += b.length; }
        @Override public void write(byte[] b, int off, int len) { count += len; }
        @Override public void writeBoolean(boolean v) { count += 1; }
        @Override public void writeByte(int v) { count += 1; }
        @Override public void writeShort(int v) { count += 2; }
        @Override public void writeChar(int v) { count += 2; }
        @Override public void writeInt(int v) { count += 4; }
        @Override public void writeLong(long v) { count += 8; }
        @Override public void writeFloat(float v) { count += 4; }
        @Override public void writeDouble(double v) { count += 8; }
        @Override public void writeBytes(String s) { count += s.length(); }
        @Override public void writeChars(String s) { count += 2L * s.length(); }
        @Override public void writeUTF(String s) { count += 2L + s.length(); } // approximation is fine for a guard
    }
}
```

- [ ] **Step 2: Extend SourbyCraftSecurityConfig**

In `SourbyCraftSecurityConfig.java`:

(a) Add fields after `creativeMaxItemNbtSize`:

```java
    // Packet guard limits (packet-guard: section)
    public static int bookEditMaxTotalChars = 65_536;
    public static int customPayloadMaxBytes = 8_192;
    public static int containerClickMaxPerTick = 20;
    public static int recipeBookMaxPerTick = 16;
```

(b) Replace `init(...)` entirely:

```java
    public static void init(File configFile) {
        CONFIG_FILE = configFile;
        if (configFile != null && configFile.exists()) {
            load();
        } else {
            saveDefault(); // fresh install: materialize full file including packet-guard
        }
        clampAll();
        loaded = true;
        SourbyLogger.info("[SourbyCraft] security limits active:"
            + " nbt=" + nbtMaxBytes + "B/d" + nbtMaxDepth + "/s" + nbtMaxStringLength + "/l" + nbtMaxListSize
            + " sign=" + signMaxLineLength + "/" + signMaxTotalChars
            + " anvil=" + anvilMaxItemNameLength
            + " creative=" + creativeMaxItemNbtSize + "B"
            + " book=" + bookEditMaxTotalChars
            + " payload=" + customPayloadMaxBytes + "B"
            + " clicks/t=" + containerClickMaxPerTick
            + " recipe/t=" + recipeBookMaxPerTick);
    }
```

(c) In `load()`, change the early-return so a missing `crash-prevention` no longer skips `packet-guard`. Replace from `Map<String, Object> crash = ...` through the end of the `creative` block with:

```java
            Map<String, Object> crash = (Map<String, Object>) root.get("crash-prevention");
            if (crash != null) {
                Map<String, Object> nbt = (Map<String, Object>) crash.get("nbt");
                if (nbt != null) {
                    nbtMaxBytes = getLong(nbt, "max-bytes", nbtMaxBytes);
                    nbtMaxDepth = getInt(nbt, "max-depth", nbtMaxDepth);
                    nbtMaxStringLength = getInt(nbt, "max-string-length", nbtMaxStringLength);
                    nbtMaxListSize = getInt(nbt, "max-list-size", nbtMaxListSize);
                }

                Map<String, Object> sign = (Map<String, Object>) crash.get("sign");
                if (sign != null) {
                    signMaxLineLength = getInt(sign, "max-line-length", signMaxLineLength);
                    signMaxTotalChars = getInt(sign, "max-total-chars", signMaxTotalChars);
                }

                Map<String, Object> anvil = (Map<String, Object>) crash.get("anvil");
                if (anvil != null) {
                    anvilMaxItemNameLength = getInt(anvil, "max-item-name-length", anvilMaxItemNameLength);
                }

                Map<String, Object> recipeBook = (Map<String, Object>) crash.get("recipe-book");
                if (recipeBook != null) {
                    recipeBookMaxPacketSize = getInt(recipeBook, "max-packet-size", recipeBookMaxPacketSize);
                }

                Map<String, Object> creative = (Map<String, Object>) crash.get("creative-item");
                if (creative != null) {
                    creativeMaxItemNbtSize = getInt(creative, "max-nbt-size", creativeMaxItemNbtSize);
                }
            }

            Map<String, Object> guard = (Map<String, Object>) root.get("packet-guard");
            if (guard != null) {
                Map<String, Object> book = (Map<String, Object>) guard.get("book-edit");
                if (book != null) bookEditMaxTotalChars = getInt(book, "max-total-chars", bookEditMaxTotalChars);
                Map<String, Object> payload = (Map<String, Object>) guard.get("custom-payload");
                if (payload != null) customPayloadMaxBytes = getInt(payload, "max-bytes", customPayloadMaxBytes);
                Map<String, Object> click = (Map<String, Object>) guard.get("container-click");
                if (click != null) containerClickMaxPerTick = getInt(click, "max-per-tick", containerClickMaxPerTick);
                Map<String, Object> recipe = (Map<String, Object>) guard.get("recipe-book");
                if (recipe != null) recipeBookMaxPerTick = getInt(recipe, "max-per-tick", recipeBookMaxPerTick);
            }
```

(d) Add `clampAll()` (private static, below `load()`), floors per spec — a yml typo must never brick normal gameplay:

```java
    /** Sanity floors/ceilings so operator typos cannot break normal gameplay or lift hard protocol caps. */
    private static void clampAll() {
        nbtMaxBytes = Math.max(65_536L, nbtMaxBytes);
        nbtMaxDepth = Math.max(16, Math.min(512, nbtMaxDepth));
        nbtMaxStringLength = Math.max(256, nbtMaxStringLength);
        nbtMaxListSize = Math.max(256, nbtMaxListSize);
        signMaxLineLength = Math.max(16, signMaxLineLength);
        signMaxTotalChars = Math.max(64, signMaxTotalChars);
        anvilMaxItemNameLength = Math.max(1, Math.min(256, anvilMaxItemNameLength));
        recipeBookMaxPacketSize = Math.max(256, recipeBookMaxPacketSize);
        creativeMaxItemNbtSize = Math.max(256, creativeMaxItemNbtSize);
        bookEditMaxTotalChars = Math.max(1_024, bookEditMaxTotalChars);
        customPayloadMaxBytes = Math.max(1_024, Math.min(32_767, customPayloadMaxBytes));
        containerClickMaxPerTick = Math.max(4, containerClickMaxPerTick);
        recipeBookMaxPerTick = Math.max(4, recipeBookMaxPerTick);
    }
```

(e) In `saveDefault()`, after the `creative-item` lines and before `w.close()`, add:

```java
            w.println("packet-guard:");
            w.println("  book-edit:");
            w.println("    max-total-chars: " + bookEditMaxTotalChars);
            w.println("  custom-payload:");
            w.println("    max-bytes: " + customPayloadMaxBytes);
            w.println("  container-click:");
            w.println("    max-per-tick: " + containerClickMaxPerTick);
            w.println("  recipe-book:");
            w.println("    max-per-tick: " + recipeBookMaxPerTick);
```

- [ ] **Step 3: Extend repo baseline `sourbycraft-security.yml`**

Append to `/Users/rheninxy/Sourby/SourbyCraft/sourbycraft-security.yml`:

```yaml
packet-guard:
  book-edit:
    max-total-chars: 65536
  custom-payload:
    max-bytes: 8192
  container-click:
    max-per-tick: 20
  recipe-book:
    max-per-tick: 16
```

- [ ] **Step 4: Compile**

Run: `./gradlew :sourbycraft-server:compileJava -q`
Expected: `BUILD SUCCESSFUL`

- [ ] **Step 5: Commit (outer repo)**

```bash
git add sourbycraft-server/src/main/java/dev/iyanz/sourbycraft/security/SecurityGuard.java \
        sourbycraft-server/src/main/java/dev/iyanz/sourbycraft/security/SourbyCraftSecurityConfig.java \
        sourbycraft-security.yml
git commit -m "security: SecurityGuard helper + packet-guard config section + clamps + saveDefault wiring"
```

---

### Task 2: NBT limits → NbtAccounter + StringTag + ListTag (nested-git commit #1)

**Files:**
- Modify: `sourbycraft-server/src/minecraft/java/net/minecraft/nbt/NbtAccounter.java`
- Modify: `sourbycraft-server/src/minecraft/java/net/minecraft/nbt/StringTag.java` (readAccounted, ~line 21)
- Modify: `sourbycraft-server/src/minecraft/java/net/minecraft/nbt/ListTag.java` (loadList ~line 31, parseList ~line 61)

**Interfaces:**
- Consumes: `SourbyCraftSecurityConfig.nbtMaxBytes/nbtMaxDepth/nbtMaxStringLength/nbtMaxListSize` (Task 1).
- Produces: `NbtAccounter.checkStringLength(int)`, `NbtAccounter.checkListSize(long)` — no-ops unless the accounter was built with content limits (network default quota only). Enforcement scope note: `unlimitedHeap()`/`uncompressedQuota()` (disk/chunk loads) are NOT content-limited — existing world data can never fail to load because of these limits.

- [ ] **Step 1: Patch NbtAccounter.java**

Replace the constructor and factory section (lines 14–33) with:

```java
    // SourbyCraft start - content-limit flag: string/list caps apply only to network-default reads
    private final boolean contentLimits;

    public NbtAccounter(final long quota, final int maxDepth) {
        this(quota, maxDepth, false);
    }

    public NbtAccounter(final long quota, final int maxDepth, final boolean contentLimits) {
        this.quota = quota;
        this.maxDepth = maxDepth;
        this.contentLimits = contentLimits;
    }
    // SourbyCraft end

    public static NbtAccounter create(final long quota) {
        return new NbtAccounter(quota, Math.min(MAX_STACK_DEPTH, dev.iyanz.sourbycraft.security.SourbyCraftSecurityConfig.nbtMaxDepth)); // SourbyCraft - config depth
    }

    public static NbtAccounter defaultQuota() {
        // SourbyCraft start - network default quota/depth from sourbycraft-security.yml, with content limits
        return new NbtAccounter(
            dev.iyanz.sourbycraft.security.SourbyCraftSecurityConfig.nbtMaxBytes,
            Math.min(MAX_STACK_DEPTH, dev.iyanz.sourbycraft.security.SourbyCraftSecurityConfig.nbtMaxDepth),
            true);
        // SourbyCraft end
    }

    public static NbtAccounter uncompressedQuota() {
        return new NbtAccounter(UNCOMPRESSED_NBT_QUOTA, MAX_STACK_DEPTH);
    }

    public static NbtAccounter unlimitedHeap() {
        return new NbtAccounter(Long.MAX_VALUE, MAX_STACK_DEPTH);
    }
```

Then add below `popDepth()`:

```java
    // SourbyCraft start - string/list content limits (network reads only)
    public void checkStringLength(final int length) {
        if (this.contentLimits && length > dev.iyanz.sourbycraft.security.SourbyCraftSecurityConfig.nbtMaxStringLength) {
            throw new NbtAccounterException("NBT string too long: " + length + " > " + dev.iyanz.sourbycraft.security.SourbyCraftSecurityConfig.nbtMaxStringLength);
        }
    }

    public void checkListSize(final long count) {
        if (this.contentLimits && count > dev.iyanz.sourbycraft.security.SourbyCraftSecurityConfig.nbtMaxListSize) {
            throw new NbtAccounterException("NBT list too large: " + count + " > " + dev.iyanz.sourbycraft.security.SourbyCraftSecurityConfig.nbtMaxListSize);
        }
    }
    // SourbyCraft end
```

- [ ] **Step 2: Patch StringTag.readAccounted**

In `StringTag.java` (~line 21), after `String data = input.readUTF();` insert:

```java
            accounter.checkStringLength(data.length()); // SourbyCraft - string length limit
```

- [ ] **Step 3: Patch ListTag (both read paths)**

In `loadList(...)` (~line 39), after `accounter.accountBytes(4L, count);` insert:

```java
            accounter.checkListSize(count); // SourbyCraft - list size limit
```

In `parseList(...)` (~line 72, the `default:` branch), after `accounter.accountBytes(4L, count);` insert the same line.

- [ ] **Step 4: Compile**

Run: `./gradlew :sourbycraft-server:compileJava -q`
Expected: `BUILD SUCCESSFUL`

- [ ] **Step 5: Nested-git commit**

```bash
git -C sourbycraft-server/src/minecraft/java add net/minecraft/nbt/NbtAccounter.java net/minecraft/nbt/StringTag.java net/minecraft/nbt/ListTag.java
git -C sourbycraft-server/src/minecraft/java commit -m "SourbyCraft security: NBT quota/depth/string/list limits from sourbycraft-security.yml"
```

---

### Task 3: Sign truncation + anvil name length (nested-git commit #2)

**Files:**
- Modify: `sourbycraft-server/src/minecraft/java/net/minecraft/server/network/ServerGamePacketListenerImpl.java` — `handleSignUpdate` (~line 3561)
- Modify: `sourbycraft-server/src/minecraft/java/net/minecraft/world/inventory/AnvilMenu.java` — `validateName` (~line 322) and `MAX_NAME_LENGTH` comment (~line 32)

**Interfaces:**
- Consumes: `SourbyCraftSecurityConfig.signMaxLineLength/signMaxTotalChars/anvilMaxItemNameLength`, `SecurityGuard.violation(...)` (Task 1).
- Produces: nothing consumed by later tasks.

- [ ] **Step 1: Patch handleSignUpdate**

Replace the existing Paper truncation loop (the `for (int i = 0; ...)` block inside `// Paper start - Limit client sign length`) with:

```java
        String[] linesArray = packet.getLines();
        // SourbyCraft start - sign line + total caps from sourbycraft-security.yml (layered under Paper's MAX_SIGN_LINE_LENGTH)
        final int lineCap = Math.min(MAX_SIGN_LINE_LENGTH > 0 ? MAX_SIGN_LINE_LENGTH : Integer.MAX_VALUE,
            dev.iyanz.sourbycraft.security.SourbyCraftSecurityConfig.signMaxLineLength);
        int totalBudget = dev.iyanz.sourbycraft.security.SourbyCraftSecurityConfig.signMaxTotalChars;
        boolean sourbyTruncated = false;
        for (int i = 0; i < linesArray.length; ++i) {
            if (linesArray[i].length() > lineCap) {
                // This handles multibyte characters as 1
                int offset = linesArray[i].codePoints().limit(lineCap).map(Character::charCount).sum();
                if (offset < linesArray[i].length()) {
                    linesArray[i] = linesArray[i].substring(0, offset); // this will break any filtering, but filtering is NYI as of 1.17
                    sourbyTruncated = true;
                }
            }
            if (linesArray[i].length() > totalBudget) {
                linesArray[i] = linesArray[i].substring(0, Math.max(0, totalBudget));
                sourbyTruncated = true;
            }
            totalBudget -= linesArray[i].length();
        }
        if (sourbyTruncated) {
            dev.iyanz.sourbycraft.security.SecurityGuard.violation("sign", this.player.getPlainTextName(), "sign text truncated to configured limits");
        }
        // SourbyCraft end
```

(The following `List<String> lines = Stream.of(linesArray)...` line and `// Paper end` stay unchanged.)

- [ ] **Step 2: Patch AnvilMenu.validateName**

Replace:

```java
    private static @Nullable String validateName(final String name) {
        String filteredName = StringUtil.filterText(name);
        return filteredName.length() <= 50 ? filteredName : null;
    }
```

with:

```java
    private static @Nullable String validateName(final String name) {
        String filteredName = StringUtil.filterText(name);
        return filteredName.length() <= dev.iyanz.sourbycraft.security.SourbyCraftSecurityConfig.anvilMaxItemNameLength ? filteredName : null; // SourbyCraft - configurable anvil name length (raise-cap: default 128 > vanilla 50)
    }
```

And update the constant declaration comment (line ~32):

```java
    public static final int MAX_NAME_LENGTH = 50; // SourbyCraft - vanilla reference only; actual cap = SourbyCraftSecurityConfig.anvilMaxItemNameLength (see validateName)
```

- [ ] **Step 3: Compile**

Run: `./gradlew :sourbycraft-server:compileJava -q`
Expected: `BUILD SUCCESSFUL`

- [ ] **Step 4: Nested-git commit**

```bash
git -C sourbycraft-server/src/minecraft/java add net/minecraft/server/network/ServerGamePacketListenerImpl.java net/minecraft/world/inventory/AnvilMenu.java
git -C sourbycraft-server/src/minecraft/java commit -m "SourbyCraft security: sign truncation caps + configurable anvil rename length"
```

---

### Task 4: Packet guards — click/recipe rate caps, book-edit, custom-payload, creative item (nested-git commit #3)

**Files:**
- Modify: `sourbycraft-server/src/minecraft/java/net/minecraft/server/network/ServerGamePacketListenerImpl.java`:
  - fields near line 330 (next to `MAX_SIGN_LINE_LENGTH`), `tick()` (~line 358), `handleRecipeBookSeenRecipePacket` (~802), `handleRecipeBookChangeSettingsPacket` (~817), `handleEditBook` (~1333), `handleContainerClick` (~3068), `handlePlaceRecipe` (~3402), `handleSetCreativeModeSlot` (~3494)
- Modify: `sourbycraft-server/src/minecraft/java/net/minecraft/server/network/ServerCommonPacketListenerImpl.java` — `handleCustomPayload` (~line 145)

**Interfaces:**
- Consumes: all Task 1 fields + `SecurityGuard.violation(...)` + `SecurityGuard.encodedSize(...)`.
- Produces: nothing consumed by later tasks.

- [ ] **Step 1: Add per-connection counters + tick reset**

Below the `MAX_SIGN_LINE_LENGTH` field (~line 328):

```java
    // SourbyCraft start - packet-guard per-tick counters (main-thread only)
    private int securityClickPackets;
    private int securityRecipePackets;
    // SourbyCraft end
```

At the very top of `public void tick()` (line ~358, before the `ackBlockChangesUpTo` block):

```java
        // SourbyCraft start - packet-guard counters reset
        this.securityClickPackets = 0;
        this.securityRecipePackets = 0;
        // SourbyCraft end
```

- [ ] **Step 2: Container-click rate cap**

In `handleContainerClick`, immediately after `PacketUtils.ensureRunningOnSameThread(packet, this, this.player.level());`:

```java
        // SourbyCraft start - container-click rate cap (note: quickcraft single-slot path re-enters this method, costing one extra count — cap default 20 absorbs it)
        final int clickCap = dev.iyanz.sourbycraft.security.SourbyCraftSecurityConfig.containerClickMaxPerTick;
        if (++this.securityClickPackets > clickCap) {
            if (this.securityClickPackets == clickCap + 1) {
                dev.iyanz.sourbycraft.security.SecurityGuard.violation("container-click", this.player.getPlainTextName(), "over " + clickCap + " click packets/tick, dropping burst");
                this.player.containerMenu.sendAllDataToRemote(); // resync client view once per burst
            }
            return;
        }
        // SourbyCraft end
```

- [ ] **Step 3: Recipe-book rate cap (3 handlers)**

Insert the identical block in `handleRecipeBookSeenRecipePacket`, `handleRecipeBookChangeSettingsPacket`, and `handlePlaceRecipe`, immediately after each handler's `PacketUtils.ensureRunningOnSameThread(...)` line:

```java
        // SourbyCraft start - recipe-book rate cap
        if (++this.securityRecipePackets > dev.iyanz.sourbycraft.security.SourbyCraftSecurityConfig.recipeBookMaxPerTick) {
            if (this.securityRecipePackets == dev.iyanz.sourbycraft.security.SourbyCraftSecurityConfig.recipeBookMaxPerTick + 1) {
                dev.iyanz.sourbycraft.security.SecurityGuard.violation("recipe-book", this.player.getPlainTextName(), "over " + dev.iyanz.sourbycraft.security.SourbyCraftSecurityConfig.recipeBookMaxPerTick + " recipe packets/tick, dropping burst");
            }
            return;
        }
        // SourbyCraft end
```

(Protocol note from spec: 26.x recipe packets are fixed-size int display-ids — `recipe-book.max-packet-size` is structurally satisfied at the codec level; the per-tick cap is the enforcement that bites.)

- [ ] **Step 4: Book-edit total cap**

At the very top of `handleEditBook` (before the `// Paper start - Book size limits` block):

```java
        // SourbyCraft start - book-edit total character cap (layered above Paper per-page limits)
        {
            int sourbyTotalChars = 0;
            for (String page : packet.pages()) sourbyTotalChars += page.length();
            if (packet.title().isPresent()) sourbyTotalChars += packet.title().get().length();
            if (sourbyTotalChars > dev.iyanz.sourbycraft.security.SourbyCraftSecurityConfig.bookEditMaxTotalChars) {
                dev.iyanz.sourbycraft.security.SecurityGuard.violation("book-edit", this.player.getPlainTextName(), sourbyTotalChars + " chars > " + dev.iyanz.sourbycraft.security.SourbyCraftSecurityConfig.bookEditMaxTotalChars + ", dropped");
                return;
            }
        }
        // SourbyCraft end
```

- [ ] **Step 5: Custom-payload size cap**

In `ServerCommonPacketListenerImpl.handleCustomPayload`, immediately after `final byte[] data = discardedPayload.data();`:

```java
        // SourbyCraft start - custom payload size cap (default 8192; vanilla hard cap 32767). BungeeCord/floodgate/WorldEdit-CUI messages are far below this.
        if (data.length > dev.iyanz.sourbycraft.security.SourbyCraftSecurityConfig.customPayloadMaxBytes) {
            dev.iyanz.sourbycraft.security.SecurityGuard.violation("custom-payload", null, identifier + " " + data.length + "B > " + dev.iyanz.sourbycraft.security.SourbyCraftSecurityConfig.customPayloadMaxBytes + "B, dropped");
            return;
        }
        // SourbyCraft end
```

- [ ] **Step 6: Creative item NBT size cap**

In `handleSetCreativeModeSlot`, immediately after the existing `dev.iyanz.sourbycraft.security.BookSanitizer.sanitize(itemStack);` line:

```java
            // SourbyCraft start - creative item encoded-NBT size cap. NOTE: filled shulker boxes encode at 2–10KB; operators who want creative shulker copying must raise crash-prevention.creative-item.max-nbt-size.
            if (!itemStack.isEmpty()) {
                final long sourbyEncoded = dev.iyanz.sourbycraft.security.SecurityGuard.encodedSize(itemStack, this.player.registryAccess());
                if (sourbyEncoded > dev.iyanz.sourbycraft.security.SourbyCraftSecurityConfig.creativeMaxItemNbtSize) {
                    dev.iyanz.sourbycraft.security.SecurityGuard.violation("creative-item", this.player.getPlainTextName(), sourbyEncoded + "B encoded NBT > " + dev.iyanz.sourbycraft.security.SourbyCraftSecurityConfig.creativeMaxItemNbtSize + "B, slot reverted");
                    if (packet.slotNum() >= 0) {
                        this.player.connection.send(new net.minecraft.network.protocol.game.ClientboundContainerSetSlotPacket(this.player.inventoryMenu.containerId, this.player.inventoryMenu.incrementStateId(), packet.slotNum(), this.player.inventoryMenu.getSlot(packet.slotNum()).getItem()));
                        this.player.connection.send(new net.minecraft.network.protocol.game.ClientboundSetCursorItemPacket(net.minecraft.world.item.ItemStack.EMPTY.copy()));
                    }
                    return;
                }
            }
            // SourbyCraft end
```

- [ ] **Step 7: Compile**

Run: `./gradlew :sourbycraft-server:compileJava -q`
Expected: `BUILD SUCCESSFUL`

- [ ] **Step 8: Nested-git commit**

```bash
git -C sourbycraft-server/src/minecraft/java add net/minecraft/server/network/ServerGamePacketListenerImpl.java net/minecraft/server/network/ServerCommonPacketListenerImpl.java
git -C sourbycraft-server/src/minecraft/java commit -m "SourbyCraft security: packet guards - click/recipe rate caps, book-edit total cap, custom-payload cap, creative NBT cap"
```

---

### Task 5: Rebuild feature patches + outer commit + release artifact

**Files:**
- Generated: `patches/minecraft/00XX-SourbyCraft-security-*.patch` (3 new files; numbering assigned by rebuild)

**Interfaces:**
- Consumes: the three nested-git commits from Tasks 2–4.

- [ ] **Step 1: Nested-git preflight**

Run: `git -C sourbycraft-server/src/minecraft/java status`
Expected: clean tree, on the patched branch, NOT mid-rebase. Three new commits on top of `0315c75 SourbyCraft SWM Level.spigotConfig saveOnLoad=false`.

- [ ] **Step 2: Rebuild feature patches**

Run: `./gradlew rebuildMinecraftFeaturePatches`
Expected: `BUILD SUCCESSFUL`; `git status patches/minecraft/` shows 3 new patch files (numbering may also renumber/scaffold neighbors — expected per paperweight-2 quirks).

- [ ] **Step 3: Outer commit**

```bash
git add patches/minecraft/
git commit -m "security: enforce sourbycraft-security.yml limits — NBT/sign/anvil/packet guards (3 feature patches)"
```

- [ ] **Step 4: Build release artifact for manual verification**

Run: `./gradlew assembleReleaseArtifacts`
Expected: `BUILD SUCCESSFUL`, jar in `release/`.

- [ ] **Step 5: Operator manual verification checklist (user boots TestServer)**

1. Copy jar → TestServer, boot. Expect INFO line: `[SourbyCraft] security limits active: nbt=... sign=... anvil=... creative=... book=... payload=... clicks/t=... recipe/t=...`
2. Delete `sourbycraft-security.yml`, reboot → file regenerated WITH `packet-guard:` section.
3. Sign: paste line > 256 chars → placed sign truncated, no kick.
4. Anvil: rename 60 chars → works (proves raise past vanilla 50); rename > 128 → rejected (no rename).
5. Creative: pull a filled shulker (>2KB) into a slot → slot reverts, console `[security:creative-item]` WARN.
6. Regression: normal join via BungeeCord+floodgate (custom-payload guard must not break handshake/plugin messaging), human-speed chest clicking, normal book edit — all unaffected.

---

## Self-Review Notes

- Spec coverage: NBT 4 limits (Task 2), sign 2 (Task 3), anvil 1 (Task 3), recipe-book max-packet-size (structurally satisfied, per-tick cap Task 4), creative (Task 4), 4 new packet-guard keys (Tasks 1+4), saveDefault fix + boot summary + clamps (Task 1), verification (Task 5). Complete.
- Enforcement scope: content limits deliberately bound to `defaultQuota()` (network) only — `unlimitedHeap()`/`uncompressedQuota()` untouched so existing world/chunk data always loads.
- Type consistency: all call sites use fully-qualified `dev.iyanz.sourbycraft.security.SourbyCraftSecurityConfig` / `SecurityGuard` statics; signatures match Task 1 definitions.
