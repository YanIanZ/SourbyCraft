<p align="center">
  <img src="assets/SourbyCraft.png" alt="SourbyCraft" width="380">
</p>

<h1 align="center">SourbyCraft — 26.2 Canvas</h1>

<p align="center"><strong>Region-threaded Minecraft 26.2 · CanvasMC engine · Cherry server-side mixins · lean SourbyLoader slim jar</strong></p>

<p align="center">
  <img src="https://img.shields.io/badge/minecraft-26.2-brightgreen?style=flat-square">
  <img src="https://img.shields.io/badge/java-25-blue?style=flat-square">
  <img src="https://img.shields.io/badge/engine-CanvasMC%20(region--threading)-8a2be2?style=flat-square">
  <img src="https://img.shields.io/badge/version-26.2--REL%20build%2038c-brightgreen?style=flat-square">
  <img src="https://img.shields.io/badge/jar-~34M%20(SourbyLoader)-green?style=flat-square">
  <img src="https://img.shields.io/badge/mixins-Cherry-e83e8c?style=flat-square">
  <img src="https://img.shields.io/badge/license-PolyForm--NC--1.0.0-lightgrey?style=flat-square">
</p>

---

## What is it

**SourbyCraft 26.2 Canvas** is a high-performance Minecraft **26.2** server. This line is a re-platform of SourbyCraft onto the **[CanvasMC](https://github.com/CraftCanvasMC/Canvas)** engine — a region-threading fork of Paper (Folia lineage) that ticks the world across CPU cores instead of one main thread. It is built with Canvas's own **weaver** toolchain and packaged as a **SourbyLoader / SourbyClip** slim jar (~34 MiB): a paperclip that downloads its heavy libraries once, SHA-256-verified, on first boot instead of bundling them, so the artifact stays tiny.

On top of the Canvas engine it adds SourbyCraft's **utility & UX layer** — a hex-colored command suite, offline **GeoIP** on `/ping`, TPS/RAM boss bars, varied **message/lang**, an **advanced auto-updater**, built-in **ViaVersion** for old clients — plus **Cherry**, a unified server-side **mixin** engine.

> **This is the Canvas benchmark line.** The self-tuning **perf engine**, **anti-xray** (SourbyEngine), and the **proxy/forwarding** config surface from the Folia line are **deferred / not present in this build** — they may be ported to Canvas later. The `release/26.2` Folia line still carries them.

Build id **38c** (`c` = Canvas), REL channel, codename **cookies**.

---

## Highlights

| | |
|---|---|
| 🧵 **CanvasMC region-threading** | Regions tick in parallel across cores; scales with player spread |
| 🍒 **Cherry mixins** | Server-side SpongePowered mixins + access-transformers + Fabric-format loading — modify server internals from a plugin, no fork. Off by default. |
| 📦 **SourbyLoader slim jar** | ~34 MiB — libraries fetched (SHA-256-verified) on first boot, not bundled |
| 🧩 **sourbyapi** | The SourbyCraft-branded API (`dev.iyanz.sourbycraft:sourbyapi`) = Canvas API over the Bukkit/Paper foundation |
| 🌍 **Offline GeoIP** | `/ping` shows player location from a local db-ip.com database — no IP leaves the server |
| 🎨 **Yellow `[SourbyCraft]` console** | Rebranded, hex-colored log prefix; truecolor banner + command output |
| 📊 **TPS / RAM boss bars** | `/tpsbar` + `/rambar` (RAM bar shows heap + swap), auto-shown to admins |
| 🔄 **Advanced auto-updater** | Channel-aware (REL/DEV/EXP), SHA-256-verified, safe staged apply |
| 🕰️ **Built-in ViaVersion** | Old clients join out of the box — ViaVersion + ViaBackwards auto-provisioned (pinned, SHA-256-verified) on first boot |
| ⚡ **spark bundled** | Canvas's built-in [spark](https://spark.lucko.me) profiler (`/spark`), latest 1.10.172 |

---

## Quick start

Requires **Java 25**.

```bash
# grab SourbyCraft-slim.jar from Releases, then:
java -Xmx4G -jar SourbyCraft-slim.jar --nogui
```

First boot fetches the externalized libraries (SourbyLoader, one-time, verified) and generates the config. Region-threading is on by default.

> **Offline first boot?** The libraries must be downloaded once. If the machine has no internet, SourbyLoader prints the exact URLs + target paths it needs.

---

## Commands

Console-runnable; each also works as `/sourbycraft:<name>` if a plugin shadows the bare name.

| Command | Does |
|---|---|
| `/tps` | TPS (native `Bukkit.getTPS()`), color-coded |
| `/mspt` | Mean ms/tick |
| `/tpsbar` · `/rambar` | Toggle the TPS / RAM (heap + swap) boss bars |
| `/ping [player]` | Latency + **GeoIP** location (offline) |
| `/ver` · `/version` | Branded build info (version, channel, GMT+7 build time) |
| `/sys` | Server / JVM / host snapshot |
| `/plugins` | SourbyCraft-styled plugin list |
| `/maxp [n]` | Show or set max players — **persists across restart** |
| `/speedtest` | Network speed (Ookla CLI, SHA-256-pinned) |
| `/update` | Auto-updater status / force a channel-aware check |

`/spark` is Canvas's built-in profiler. **Max-player bypass:** players with `sourbycraft.maxplayers.bypass` (or ops) can join a full server.

---

## Cherry — server-side mixins

**Cherry** is SourbyCraft's unified mixin engine, running natively inside SourbyClip (no separate launcher jar). It merges **LeavesMC (Leavesclip)** — SpongePowered Mixin + access-wideners + MixinExtras + conditional-mixins — with **CraftCanvasMC (Horizon)**'s access-transformer engine, and adds **Fabric-format** discovery (`fabric.mod.json` mixins + `*.mixins.json` + refmaps, server-side only).

Enable with `-Dcherry.enable.mixin=true`. A plugin (also a normal Paper plugin) drops a `cherry-plugin.json` declaring an optional `mixin` block and/or an `access-transformers` list. It loads Fabric-format server-side mixin/AT/access-widener declarations — it does **not** run full Fabric mods.

Full docs, javadocs, and the plugin-author guide: **https://github.com/YanIanZ/Cherry**

---

## Configuration

Two independent config surfaces coexist cleanly:

- **SourbyCraft utility layer** → `sourbycraft_config/sourbycraft_global_config.toml` (nightconfig TOML) — messages, `/maxp` persistence, the auto-updater, ViaVersion auto-provision.
- **Canvas engine** → `config/canvas-server.yml` + `config/canvas-worlds.yml` (region scheduler, tick rate, autosave, …). Defaults are stabilized for production (e.g. region-scheduler `guard-severity: LOG` instead of the crash-prone `THROW`).

### Built-in ViaVersion / ViaBackwards (old-client support)

Old clients join with **zero manual install**. On first boot SourbyCraft downloads the pinned ViaVersion + ViaBackwards jars into `plugins/` (each SHA-256-verified, https-only, like the slim-jar libraries) *before* the plugin manager scans, so they load the same boot.

- **Change the floor:** `plugins/ViaVersion/config.yml` → `block-versions` (default `["<1.20"]`).
- **Toggle:** `[viaversion] auto-provision` in the unified TOML (default `true`). Set `false` to manage Via yourself or run fully offline.
- **Idempotent:** never re-downloads a present/verified jar and never overwrites your config edits.

---

## Build from source

Requires **JDK 25** and git. This line uses Canvas's **weaver** patcher (not SourbyPatcher, which is unused here).

```bash
# apply Canvas + SourbyCraft patches, then build the slim jar
./gradlew applyAllPatches
./gradlew slimServerJar    # -> build/libs/SourbyCraft-slim.jar
```

`applyAllPatches` materializes the Canvas + Paper sources from the pinned upstreams and applies SourbyCraft's patches; `slimServerJar` strips the externalized libraries and injects the SourbyLoader bootstrap.

---

## Release lines

| Line | Focus | Base | Branch |
|---|---|---|---|
| **26.1.2** | Skyblock / minigames | Paper + SWM in-memory worlds | `release/26.1.2` |
| **26.2 (Folia)** | Full perf engine + anti-xray + proxy | Luminol 26.2 | `release/26.2` |
| **26.2 Canvas** *(this)* | **CanvasMC engine + Cherry mixins** | CanvasMC 26.2 (via weaver) | `release/26.2-canvas` |

The auto-updater serves this Canvas line as the current REL builds (`v26.2-r36` onward).

---

## Credits & license

Built on **[CanvasMC](https://github.com/CraftCanvasMC/Canvas)** (region-threading fork of [Paper](https://github.com/PaperMC/Paper), Folia lineage). Cherry merges **[LeavesMC](https://github.com/LeavesMC) (Leavesclip)** + **[CraftCanvasMC/Horizon](https://github.com/CraftCanvasMC/Horizon)** mixin tooling. Profiler by **[spark](https://spark.lucko.me)**.

Licensed under **PolyForm Noncommercial 1.0.0** — see [`LICENSE`](LICENSE).
