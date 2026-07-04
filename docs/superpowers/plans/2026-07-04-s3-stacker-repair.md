# S3 Stacker Repair — Implementation Plan

Spec: docs/superpowers/specs/2026-07-04-s3-stacker-repair-design.md
Repo: /Users/rheninxy/Sourby/SourbyCraft, branch release/26.1.2 (commit direct).
Outer repo only — NO nested git, NO patch rebuild.

## Task 1: Config alias + fields + EntityStacker wiring

**Files:**
- Modify: `sourbycraft-server/src/main/java/dev/iyanz/sourbycraft/SourbyCraftConfig.java`
- Modify: `sourbycraft-server/src/main/java/dev/iyanz/sourbycraft/wildstacker/EntityStacker.java`

- [ ] **Step 1: New config fields** — in `SourbyCraftConfig.java`, directly under
  `public static java.util.List<String> stackerBlacklist = ...` (line ~212):

```java
    public static boolean stackerHologram = true;
    public static boolean stackerLosCheck = true;
```

- [ ] **Step 2: Alias seeding + parse** — replace the existing stacker block
  (lines ~366-379, from the `// Entity stacker config (re-port era v10+)`
  comment through the blacklist `catch (Throwable ignored) {}`) with:

```java
        // Entity stacker config (re-port era v10+)
        // S3: legacy performance.wildstacker.* keys (pre-26.1.2 operator files) seed the
        // canonical stacker.* keys when those are absent. Explicit stacker.* always wins.
        // Legacy keys stay in the file as documented aliases — never deleted.
        if (!config.isSet("stacker.enabled") && config.getBoolean("performance.wildstacker.enabled", false)) {
            config.set("stacker.enabled", true);
        }
        if (!config.isSet("stacker.hologram") && config.isSet("performance.wildstacker.hologram")) {
            config.set("stacker.hologram", config.getBoolean("performance.wildstacker.hologram", true));
        }
        if (!config.isSet("stacker.los-check") && config.isSet("performance.wildstacker.los-check")) {
            config.set("stacker.los-check", config.getBoolean("performance.wildstacker.los-check", true));
        }
        stackerEnabled = getBoolean("stacker.enabled", stackerEnabled);
        stackerRadius = getDouble("stacker.radius", stackerRadius);
        stackerMaxStack = getInt("stacker.max-stack", stackerMaxStack);
        stackerHologram = getBoolean("stacker.hologram", stackerHologram);
        stackerLosCheck = getBoolean("stacker.los-check", stackerLosCheck);
        try {
            java.util.List<?> raw = config.getList("stacker.blacklist");
            if (raw == null) {
                set("stacker.blacklist", stackerBlacklist);
            } else {
                java.util.List<String> parsed = new java.util.ArrayList<>();
                for (Object o : raw) if (o != null) parsed.add(o.toString());
                stackerBlacklist = parsed;
            }
        } catch (Throwable ignored) {}
```

- [ ] **Step 3: EntityStacker fields + reload** — in `EntityStacker.java`,
  under `private static volatile int MAX_STACK_MULTIPLIER = 100;` add:

```java
    private static volatile boolean HOLOGRAM = true;
    private static volatile boolean LOS_CHECK = true;
    private static volatile java.util.Set<org.bukkit.Material> BLACKLIST = java.util.Set.of();
```

Replace `reload()` with:

```java
    public static void reload() {
        ENABLED = SourbyCraftConfig.stackerEnabled;
        RADIUS = Math.max(0.5, SourbyCraftConfig.stackerRadius);
        MAX_STACK_MULTIPLIER = Math.max(1, SourbyCraftConfig.stackerMaxStack);
        HOLOGRAM = SourbyCraftConfig.stackerHologram;
        LOS_CHECK = SourbyCraftConfig.stackerLosCheck;
        // Blacklist semantic: item materials excluded from stacking. Legacy
        // EntityType names (PLAYER, WITHER, ...) don't resolve to materials
        // and are ignored harmlessly.
        java.util.Set<org.bukkit.Material> parsed = new java.util.HashSet<>();
        for (String name : SourbyCraftConfig.stackerBlacklist) {
            org.bukkit.Material m = org.bukkit.Material.matchMaterial(name);
            if (m != null) parsed.add(m);
        }
        BLACKLIST = java.util.Set.copyOf(parsed);
    }
```

- [ ] **Step 4: register() log line** — extend the existing
  `plugin.getLogger().info("[stacker] item stacker ...")` to append
  `" hologram=" + HOLOGRAM + " losCheck=" + LOS_CHECK + " blacklist=" + BLACKLIST.size()`
  before the closing `)` text (keep existing content).

- [ ] **Step 5: Blacklist gates**
  - `onItemSpawn`: after the `if (newStack == null || newStack.getType().isAir()) return;` line add
    `if (BLACKLIST.contains(newStack.getType())) return;`
  - `periodicMergeSweep`: after `if (sa == null || sa.getType().isAir()) continue;` add
    `if (BLACKLIST.contains(sa.getType())) continue;`
    (checking `a` suffices — `isSimilar` forces `b` to the same type)
  - `onItemMerge`: at the top, after the `if (!ENABLED) return;` line add
    `if (BLACKLIST.contains(e.getTarget().getItemStack().getType())) return;`

- [ ] **Step 6: LOS gates** — both call sites become:
  - spawn path: `if (LOS_CHECK && !hasLineOfSight(loc, nearItem.getLocation())) continue;`
  - sweep path: `if (LOS_CHECK && !hasLineOfSight(la, lb)) continue;`

- [ ] **Step 7: Hologram gate** — at the top of `updateHologram(Item item)` insert:

```java
        if (!HOLOGRAM) { removeHologram(item); return; }
```

  (existing `if (OWNER == null || HOLOGRAM_KEY == null) return;` stays right after)

- [ ] **Step 8: Compile** — `./gradlew :sourbycraft-server:compileJava -q` → BUILD SUCCESSFUL

- [ ] **Step 9: Outer commit** —
  `git add sourbycraft-server/src/main/java/dev/iyanz/sourbycraft/SourbyCraftConfig.java sourbycraft-server/src/main/java/dev/iyanz/sourbycraft/wildstacker/EntityStacker.java`
  `git commit -m "stacker: honor legacy performance.wildstacker.* aliases + wire blacklist/hologram/los-check toggles"`
