# UniverseSpigot gated-patch template

Source-of-truth for every patch in sub-projects 01-08 (`particles`, `sounds`, `misc`,
`limiters`, `behavior`, `fixes`, `performance`, `async`, `combat`, `experimental`,
`developer`). Read this before writing a new `us-*` patch.

## Anatomy of the check

Each UniverseSpigot key turns on or off a deviation from vanilla Paper. The deviation
lives in a patch whose only purpose is to wrap the vanilla call site with a config check:

```java
if (SourbyCraftConfig.ymlBool("category.subgroup.key", false)) {
    // SourbyCraft branch — new (UniverseSpigot-imported) behavior
} else {
    // vanilla branch — byte-for-byte identical to pre-patch code
}
```

The vanilla branch must be left untouched apart from being moved into an `else` block.
This is what preserves Paper-vanilla behavior when the operator does not opt in.

## Where to put the check

Find the vanilla call site by reading the generated mojmap source under
`paper-server/src/main/java/net/minecraft/...`. The convention is:

- For event-driven features (particle emit, sound play, event broadcast): wrap the
  single call line.
- For tick-driven features (entity tick, brain update): wrap the loop body so the
  vanilla path remains hot when the toggle is off.

If you cannot identify a single call site, the design is wrong — refactor the spec
to either pick a more surgical insertion point or to use multiple smaller toggles.

## Bool-caching pattern (mandatory for hot loops)

`SourbyCraftConfig.ymlBool` resolves to a HashMap lookup (~50 ns). At tick rate this
is acceptable per-call, but inside per-entity or per-block loops the lookup will
dominate. **Cache the bool into a local at method entry** when the check is inside
a loop:

```java
// vanilla:
public void tick() {
    for (Entity e : this.entities) {
        this.emitFallParticle(e);
    }
}

// gated patch:
public void tick() {
    final boolean particlesOff = SourbyCraftConfig.ymlBool("particles.disableFallParticles", false); // SourbyCraft - US import
    for (Entity e : this.entities) {
        if (!particlesOff) {
            this.emitFallParticle(e);
        }
    }
}
```

For event-driven (non-loop) sites, an inline `ymlBool` call is fine.

## Diff hygiene

Every line a UniverseSpigot patch adds gets a trailing comment:

```java
final boolean particlesOff = SourbyCraftConfig.ymlBool("particles.disableFallParticles", false); // SourbyCraft - US import
```

This makes `grep -rn "SourbyCraft - US import"` the canonical way to enumerate all
UniverseSpigot insertions.

## Commit naming

```
patch: us-<category>-<subgroup> — gated <feature>
```

Examples:

- `patch: us-particles-fall-death — gated fall + death particle emit`
- `patch: us-behavior-spawner — gated spawner light + nearby-player checks`
- `patch: us-performance-hoppers — gated hopper throttle for full target container`

## Patch filename

`patches/server/NNNN-us-<category>-<subgroup>.patch` where `NNNN` is the next free
patch number under `patches/server/`. Foundation reserves `0034`.

## What NOT to do

- Do not change branding identity inside a US patch. Branding lives in patch 0003
  and is already correct.
- Do not add new fields to `SourbyCraftConfig` for each key. Use `ymlBool`/`ymlInt`/
  `ymlDouble`/`ymlStringList`/`ymlEntityTypeMap` directly. The dotted-path keys are
  the schema.
- Do not call `ymlBool` from a constructor or static initializer of an NMS class —
  the baseline yml may not be loaded yet at class-init time. Push the call into the
  first runtime method that uses the value.
- Do not introduce per-world or per-dimension config. Per-world support is deferred
  to a separate spec.
- Do not introduce hot-reload. Config is boot-time only; the yml header documents
  the restart requirement.
