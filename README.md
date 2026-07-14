<p align="center">
  <img src="assets/SourbyCraft.png" alt="SourbyCraft Folia" width="380">
</p>

<h1 align="center">SourbyCraft — 26.2 Folia</h1>

<p align="center"><strong>Regionized multithreading · Self-tuning per-region performance · Lean on CPU &amp; RAM · Built for 200+ players</strong></p>

<p align="center">
  <img src="https://img.shields.io/badge/minecraft-26.2-brightgreen?style=flat-square">
  <img src="https://img.shields.io/badge/java-25-blue?style=flat-square">
  <img src="https://img.shields.io/badge/base-Folia%20·%20Luminol%2026.2-orange?style=flat-square">
  <img src="https://img.shields.io/badge/version-26.2--REL-brightgreen?style=flat-square">
  <img src="https://img.shields.io/badge/jar-33.7M%20(SourbyLoader)-green?style=flat-square">
  <img src="https://img.shields.io/badge/deploy-Docker%20·%20Pterodactyl%20·%20Pelican%20·%20k8s-2496ed?style=flat-square">
  <img src="https://img.shields.io/badge/license-PolyForm--NC--1.0.0-lightgrey?style=flat-square">
</p>

---

## What is it

**SourbyCraft 26.2 Folia** is a high-performance Minecraft **26.2** server — a full **Paper → Folia re-platform** of the SourbyCraft project. It runs on true **regionized multithreading** (the world is split into independently-ticked regions across CPU cores, no single main thread), built on the [Luminol](https://github.com/LuminolMC/Luminol) 26.2 Folia base.

It is compiled with an **own build toolchain — SourbyPatcher** (a fork of the Luminol/paperweight patcher) — and packaged as a **SourbyLoader** slim jar: a **33.7 MiB** paperclip that downloads its heavy libraries once on first boot instead of bundling them, so the artifact stays tiny.

On top of that base it adds a **self-tuning per-region performance engine**, a full SourbyCraft **command &amp; UX suite** (hex-colored), offline **GeoIP**, a varied **message/lang** layer, and an **advanced auto-updater** — all **100% Folia-native**.

---

## Highlights

| | |
|---|---|
| ⚡ **Regionized multithreading** | Folia base — regions tick in parallel across cores; scales with player spread |
| 🎛️ **Self-tuning perf engine** | Per-region-aggregate sensor → performance **Tier** → knobs enforced onto the base's optimizations, live |
| 📦 **SourbyLoader slim jar** | 33.7 MiB — libraries fetched (SHA-256 verified) on first boot, not bundled |
| 🧰 **SourbyPatcher** | Own build toolchain; SourbyCraft owns the full chain (patcher + Folia base) |
| 🌍 **Offline GeoIP** | `/ping` shows player location from a local db-ip.com database — no IP leaves the server |
| 🎨 **Hex UI everywhere** | Truecolor banner, advisors, command output, and rebranded log prefixes |
| 🗺️ **Varied messages** | Configurable, multi-variant server-full / MOTD / join-leave lang |
| 🔄 **Advanced auto-updater** | Channel-aware (REL/DEV/EXP), SHA-256-verified, safe staged apply |
| 🕰️ **Built-in ViaVersion** | Old clients (**1.20 → server's 26.2 / 1.21.9**) join out of the box — ViaVersion + ViaBackwards auto-provisioned (pinned, SHA-256-verified) on first boot |
| 🚀 **Auto-CDS** | Class-Data-Sharing that adapts to bare-metal / Docker / k8s / systemd / panels |
| 🧵 **SIMD + Pufferfish** | Vectorized ops (`jdk.incubator.vector`) + Pufferfish optimizations enabled |

---

## Quick start

Requires **Java 25**.

```bash
# grab SourbyCraft-26.2-REL.jar from Releases, then:
java -Xmx4G -jar SourbyCraft-26.2-REL.jar --nogui
```

First boot fetches ~32 MiB of libraries (SourbyLoader, one-time, verified) and generates the config. That's it — regionized threading is on by default.

> **Offline first boot?** The libraries must be downloaded once. If the machine has no internet, download them manually — SourbyLoader prints the exact URLs + target paths it needs.

---

## Commands

Console-runnable; each also works as `/sourbycraft:<name>` if a plugin shadows the bare name.

| Command | Does |
|---|---|
| `/perf` | Live perf engine — Tier, TPS (1s/30s/5m), MSPT, and the active knobs |
| `/ping [player]` | Latency + **GeoIP** location (offline) |
| `/ver` · `/version` | Branded build info (version, channel, GMT+7 build time) |
| `/sys` | Server/JVM/host snapshot |
| `/plugins` | SourbyCraft-styled plugin list |
| `/maxp [n]` | Show or set max players — **persists across restart** |
| `/speedtest` | Network speed (Ookla CLI, SHA-256-pinned) |
| `/sparkview` | Quick [spark](https://spark.lucko.me) profiler view |

**Max-player bypass:** players with `sourbycraft.maxplayers.bypass` (or ops) can join a full server.

---

## Performance engine

Folia has no global tick, so the engine works on **aggregates**:

1. A **sensor** samples server-wide load (`getTPS()` / MSPT / heap / GC) on the global-region scheduler.
2. A **self-tune controller** classifies a server-wide **Tier** (GREEN → YELLOW → RED …) with warmup + hysteresis.
3. On tier change, a **KnobEnforcer** pushes tuned values onto the base's own Folia-safe optimizations (projectile chunk-load caps, goal-selector throttle, …) — so enforcement runs on the base's already-optimized paths, never fighting Luminol.

All Folia-native (region/async schedulers, no legacy Bukkit global scheduler), 0-allocation sample path.

---

## Configuration

One unified file: **`sourbycraft_config/sourbycraft_global_config.toml`**. It carries Luminol's ~60 optimization modules **and** SourbyCraft's `perf.*`, `messages.*`, `misc.auto_update.*`, and `max-players` keys — a single place to tune everything, with room for more.

### Built-in ViaVersion / ViaBackwards (old-client support)

Old clients join with **zero manual install**. On first boot SourbyCraft downloads the pinned **ViaVersion 5.10.0** + **ViaBackwards 5.10.0** jars into `plugins/` (each **SHA-256-verified**, https-only, exactly like the slim-jar libraries) *before* the plugin manager scans — so they load and enable the same boot. Both are Folia builds (`folia-supported: true`).

- **Supported client range:** oldest **1.20** → the server's native **26.2 (MC 1.21.9, protocol 776)**. Clients older than 1.20 are refused with a clean message.
- **Change the floor:** `plugins/ViaVersion/config.yml` → `block-versions` (default `["<1.20"]`). Lower it (e.g. `["<1.16.5"]`) to allow older clients.
- **Toggle:** `[viaversion] auto-provision` in the unified TOML (default `true`). Set `false` if you manage Via yourself or run fully offline.
- **Idempotent:** never re-downloads a present/verified jar and never overwrites your config edits; an existing `ViaVersion-*.jar` / `ViaBackwards-*.jar` (yours or ours) is left untouched.

### Behind a proxy (Velocity / XCord / FlameCord / BungeeCord / Waterfall)

SourbyCraft uses Paper/Folia's **native** IP forwarding — nothing is reimplemented. The unified TOML adds a clean `proxy.*` surface over it, applies your choice to the underlying Paper/Spigot settings **at boot, before the network binds**, validates the result, and logs the active mode as one hex line (`[SourbyCraft] proxy: velocity (modern forwarding)` / `bungeecord (legacy forwarding)` / `direct (no proxy)`).

Set `proxy.mode` in `sourbycraft_config/sourbycraft_global_config.toml`, then **`online-mode=false`** in `server.properties` (the proxy authenticates), then **restrict the backend port to your proxy** (see anti-bypass below).

**Velocity (modern, secure — recommended):**

```toml
[proxy]
mode = "velocity"
[proxy.velocity]
secret = ""          # leave blank here; set PAPER_VELOCITY_SECRET instead (never commit a secret)
online-mode = true
```

The secret must **exactly** match your proxy's `forwarding.secret`. Prefer the `PAPER_VELOCITY_SECRET` env var over storing it in the file. If the mode is `velocity` but no secret is found, boot warns and Paper drops proxied logins.

**XCord / FlameCord / BungeeCord / Waterfall (legacy):** XCord and FlameCord are BungeeCord *forks*, so all four use the **same legacy path**:

```toml
[proxy]
mode = "bungeecord"
[proxy.bungeecord]
online-mode = true
```

(Equivalent to `settings.bungeecord: true` in `spigot.yml` + `proxies.bungee-cord.online-mode` in `paper-global.yml` — SourbyCraft sets both for you at boot.)

**Anti-bypass (required when proxied):** a direct connection to the backend can spoof any identity. The **primary defense is a firewall** — bind the backend port to the proxy IP only (iptables / security-group / `server-ip=127.0.0.1` for a same-host proxy). As a fallback for hosts where you can't firewall, an **opt-in** allowlist listener rejects any direct (non-proxy) source IP:

```toml
[proxy]
anti-bypass-enabled = true
allowed-ips = ["127.0.0.1", "10.0.0.5"]   # your proxy IP(s)
```

It is **off by default** — enabling it registers a `PlayerLoginEvent` listener, which disables the config-phase fast join path and slows every join. Leave it off and firewall instead where you can. Detected misconfigurations (velocity without a secret, both modes enabled, proxy active while `online-mode=true`, empty allowlist) are warned at boot.

---

## Deploy

Reference **Dockerfile** + entrypoint included (non-root, UTF-8 console). **Auto-CDS** detects the environment and does the safe thing automatically:

- **Bare-metal** → forks one child JVM with `-XX:+AutoCreateSharedArchive` (faster restarts).
- **Docker / Pterodactyl / Pelican / Kubernetes / systemd / LXC / Podman / Nomad / cloud / CI** → boots inline (never double-commits heap under a cgroup cap or hides behind an orchestrator PID) and prints a copy-paste single-JVM CDS flag.

Override with `-Dsourbycraft.cds.mode=auto|flag|fork|off`. SIMD is auto-enabled on the fork path; on managed panels add `--add-modules=jdk.incubator.vector` (the server prints the hint).

---

## Build from source

Requires **JDK 25** and git.

```bash
# 1. publish the SourbyPatcher build plugin
cd sourbypatcher && ./gradlew publishToMavenLocal && cd ..

# 2. set up the MaplePile submodule (Folia leafpile dep)
bash scripts/setup_maple_pile.sh

# 3. apply patches, then build the slim jar
./gradlew applyAllPatches
./gradlew slimPaperclipJar   # -> build/libs/SourbyCraft-slim.jar
```

`applyAllPatches` materializes the Folia server sources from the pinned upstream; `slimPaperclipJar` strips the externalized libraries and injects the SourbyLoader bootstrap.

---

## Release lines

| Line | Focus | Base | Branch |
|---|---|---|---|
| **26.1.2** | Skyblock / minigames | Paper + SWM in-memory worlds | `release/26.1.2` |
| **26.2** *(this)* | **Folia** | Luminol 26.2 — regionized multithreading | `release/26.2` |

The pre-Folia Paper tree is preserved at tag `paper-26.2-pre-folia`.

---

## Known issues

- **Base `/stop` NPE** (`CommandSourceStack.getLevel()` null) on the vanilla shutdown console path — an upstream Folia/Luminol issue, not this fork. Clean shutdown still works via signal; a CDS archive write on `/stop` may be skipped.

---

## Credits &amp; license

Built on [Folia](https://papermc.io/software/folia) / [Luminol](https://github.com/LuminolMC/Luminol) (+ Kaiiju, Leaves, Lithium, Pufferfish, MaplePile) and [Paper](https://github.com/PaperMC/Paper). SourbyPatcher forks the Luminol paperweight toolchain.

Licensed under **PolyForm Noncommercial 1.0.0** — see [`LICENSE`](LICENSE).
