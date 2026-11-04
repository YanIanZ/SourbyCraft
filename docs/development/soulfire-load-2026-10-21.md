# SoulFire as a load generator (2026-10-21) — PARTIAL, NOT CERTIFIED

Status: install and loopback capacity smoke done; the panel run was **stopped by the PM** after one
10-bot window (2 valid samples), so the panel result is **NOT RUN** for 25/50/100 bots. Nothing here
is a benchmark of SourbyCraft; it sizes what the VPS can drive and shows the tool works against the panel.

## Tool

| Item | Value |
| --- | --- |
| Tool | SoulFire CLI 2.10.4 (soulfiremc-com/SoulFire), released 2026-10-08 |
| Release | https://github.com/soulfiremc-com/SoulFire/releases/tag/2.10.4 |
| Jar | `SoulFireCLI-2.10.4.jar`, 1052487992 bytes |
| SHA-256 | `33c206470c1340713574a21fac05b022f544c492d55020e4fd8f30643e5fe7ac` (equals the GitHub release digest) |
| Licence | **AGPL-3.0** (GitHub licence file). Use it as a tool on servers we own; do not bundle or redistribute it in SourbyCraft artifacts. The README says: use only on servers you own or may test. |
| Location | VPS `/opt/sourby-agent/soulfire/` (jar, `home/` = its `~/.soulfire` 1.4 GB: Minecraft 26.3 client jar, 74 Fabric mods, assets, natives; `tools/`; raw `out/`, `run/`, `logs-smoke/`) |
| Java | 25 (`/opt/sourby-agent/jdk`, Temurin 25.0.4.1); needs `-Xmx>=3G` (1 GB jar is read into memory; `-Xmx1G` fails with OutOfMemoryError) |
| Protocol | default is 26.3 (`RELEASE|777`); always pass `--bot-protocol-version=RELEASE|776` for SourbyCraft 26.2 |

What it is: the "CLI" starts a full SoulFire backend plus a headless Minecraft **26.3 client** (Fabric, 74 mods,
ViaFabricPlus, software Vulkan via lavapipe) in one JVM. Start-up takes about 3 minutes on 4 cores and fails
("Timed out while waiting for the base Minecraft instance to finish loading resources") at CPUQuota=250%.
It writes to `$HOME/.soulfire` (we set `HOME`/`-Duser.home` to `/opt/sourby-agent/soulfire/home`) and
persists an instance in `soulfire.sqlite`, which restores on the next start; we deleted it before every run and at the end.
Bots = lines of the account file (offline format: one name per line). Switch flags are presence flags.

## Commands (no secrets)

```bash
# VPS, as root; scope nice 10. Accounts file: Bench000..Bench(N-1), one per line.
systemd-run --scope --unit sf-client-X -p CPUQuota=400% -p MemoryMax=7G -p MemorySwapMax=0 \
  nice -n 10 /opt/sourby-agent/jdk/bin/java -Xms512M -Xmx5G -XX:+UseG1GC -Duser.home=/opt/sourby-agent/soulfire/home \
  -Dsf.flags.v2=true -Dterminal.jline=false -Dterminal.ansi=false -jar SoulFireCLI-2.10.4.jar \
  --account-file=accounts.txt --account-type=OFFLINE --bot-address=HOST:PORT '--bot-protocol-version=RELEASE|776' \
  --bot-join-delay-min=500 --bot-join-delay-max=1000 --start        # stdin kept open (FIFO): it is an interactive shell
```

Helper scripts (VPS `/opt/sourby-agent/soulfire/tools/`, copies in the raw directory): `launch_sf.sh`, `step.sh`
(SoulFire at 400% for the start-up, then `systemctl set-property --runtime ... CPUQuota=250%`), `sampler.py`
(cgroup CPU/RSS every 10 s; server `/perf tick|region|cpu|memory` + `list` through the server's stdin FIFO), `panel_sf.sh`, `run_panel.sh`.

## Phase A: loopback capacity smoke (client and server share the VPS) — NOT CERTIFIED

Server: official v26.2-r47 jar (SHA-256 `411eadb0...938d`), JDK 25, `-Xmx3G`, scope CPUQuota=150% (250% after the first
failed attempt), loopback port 25610, online-mode=false, no whitelist, connection-throttle -1 and per-address guard raised,
fresh world seed 440044 with a `forceload` of 9x9 chunks at spawn, difficulty easy (bots were killed by zombies: 39 deaths, they respawn),
ViaVersion/ViaBackwards/ProtocolLib auto-provisioned. Bots: SoulFire defaults (render distance 2), join delay 0.5-1 s, one
step per SoulFire launch (SoulFire at 250% after start-up; 350% for 100). Samples every 10 s over the join ramp plus about 3 minutes
(14-21 samples per step). **Client and server compete for the same 6 vCPUs and the server is a fresh world, so the server columns
are not a server measurement; the question answered is only how many bots SoulFire can drive.** Docker (dengarin-web, postgres, redis) was running and idle.

| Bots requested | Connected at end (max) | SoulFire CPU median / max (% of one core) | SoulFire RSS median / max (MB) | Server CPU median (% of one core) | Server TPS median / min | Server avg MSPT median / max | Samples |
|---:|---:|---:|---:|---:|---:|---:|---:|
| 10 (SF cap 250%) | 10 (10) | 180 / 218 | 2985 / 3015 | 143 | 13.3 / 1.5 | 48.5 / 603 | 14 |
| 25 (SF cap 250%) | 25 (25) | 184 / 243 | 3390 / 4021 | 131 | 9.7 / 3.6 | 77.4 / 300 | 17 |
| 50 (SF cap 250%) | 50 (50) | 216 / 234 | 3198 / 3882 | 138 | 16.7 / 3.4 | 36.3 / 3088 | 19 |
| 100 (SF cap 350%) | 88 (88) | 241 / 319 | 3595 / 4271 | 145 | 16.3 / 3.7 | 46.4 / 436 | 21 |

Findings:
- **No bot disconnects at 10, 25, 50 or 88 connected** (zero "Disconnected" lines in SoulFire's log for those steps; the server logged 2 "Timed out" and 199 "Disconnected" over all steps, the latter being the scopes stopping between steps; 0 ERROR lines; clean stop).
- **SoulFire's cost is mostly fixed**: about 1.8-2.4 cores and 3-3.7 GB RSS (4.3-6.2 GB cgroup incl. page cache) from 10 bots up to 88; it hit its CPU cap at 250%, so per-bot cost is not separable from this data. A first 10-bot attempt at CPUQuota=250% for start-up did not start at all.
- **Ramp speed**: about 2.4 s per bot in practice (join delay 0.5-1 s plus login), so 100 bots needs about 4 minutes; at the end of the 300 s window 88 of 100 were connected (still ramping, not failing). 150+ bots were not tried.
- Server side: worst-region TPS median 9.7-16.7 and MSPT median 36-77 ms with 1 active region, p99 up to seconds; this is fresh-world generation + zombies + shared CPU, not comparable with bench_panel.py numbers.
- Safe size for one SoulFire on this VPS (400% quota, nothing else running): 50 bots clearly, 100 plausible with a 5 min ramp; not proven beyond 88.

## Phase B: panel — PARTIAL

The `.panel` lock was taken after sonnet-vpsbench released it. Before anything: 0 players online, `/ver` showed
`Git: 26.2@2c8660b75ee1ac66cd7a89fa83a170ada719dffa`, Build 47 (the PM's required check). Run through a merged copy of
`bench_panel.py` (sonnet-vpsbench's version + the `--external-clients-cmd` change, below) on the VPS, `players-N` plan, `--max-players N`,
SuperiorSkyblock2 `--edit` as in the vpsbench report, panel `hyka.parama.cloud:25555`, TCP connect RTT VPS to panel (10 attempts): min 83.8 / median 100.1 / max 213.7 ms
(second window: 80.7 / 129.9 / 1642.5 ms).

| Attempt | Result |
| --- | --- |
| players-50 plan with 25 SoulFire bots | SoulFire died 8 s after start ("SessionProtocolNegotiationException ... session creation timed out" talking to its own local RPC server, 1 failure in 8 starts); bench_panel aborted on 0 clients and restored. Retry loop added to `panel_sf.sh` afterwards. About 40 min of panel time lost, 1558 setup commands at 0.35 s each. |
| players-10 plan, 10 SoulFire bots | 10 of 10 in play. 2 valid samples: worst-region TPS 20.0 / 20.0, avg MSPT 5.16 / 5.47 ms, p99 7.43 ms, recent max 11.2 / 15.8 ms, 8 active regions, process CPU 8.7 / 9.5 %, heap used 2.4G / 3.3G. SoulFire during the window: median 236 % of one core (175-343), RSS median 3.7 GB (max 3.9 GB), cgroup max 6.2 GB. **Stopped by the PM after about 2 minutes of the 300 s window**; samples 3 and 4 are after SoulFire was stopped and are not load readings. |
| players-25/50/100 | NOT RUN |

For orientation only: sonnet-vpsbench measured players-10 with its Python clients at 72-78 % process CPU (8 CPUs) on r47; here with SoulFire's 10 real clients it read 8.7-9.5 % in 2 samples. Different client behaviour (SoulFire's bots mostly stand still with render distance 2; the plan moves/teleports the Python clients to the entity sites), 2 samples, so no conclusion.

End state: panel restored and verified after the abort (state running; `server.properties` online-mode=true, white-list=false, enforce-whitelist=false, level-name=world, max-players=20, allow-flight=false; `bukkit.yml` connection-throttle 4000; `sourbycraft-security.yml` per-address 20 / 5 mode LOG; SuperiorSkyblock2 spawn `world`; `whitelist.json` 83 bytes; `bench_panel` reported `properties_identical: true`). The bench world `bench/` and the Essentials/SSB2 `Bench000-009` records remain on the panel, as before. Lock released.

## Tool change for the PM (not applied, not committed)

**Status 2026-10-25:** applied. `scripts/external_swarm.py` and the `bench_panel.py` options (`--external-clients-cmd`,
`relaxed_connection_guard`) were committed in `d6dddd9` (2026-10-22, "feat(bench): drive bench clients with an
external command (SoulFire)"), after the vpsbench changes (`a3ebda7`). The text below is the state of 2026-10-21.

Worktree `sc-worktrees/sonnet-soulfire`: new `scripts/external_swarm.py` (`ExternalSwarm`: start a command in its own session, count clients from the server's `list`, SIGTERM/SIGKILL the group), `scripts/bench_panel.py`
(`--external-clients-cmd`, `--external-clients-timeout`, `relaxed_connection_guard`: per-address limits in `sourbycraft-security.yml` raised for the window and restored with the other files; the throttle in bukkit.yml is already handled), `scripts/test_bench_panel.py`
(+4 tests, 9 pass). The worktree is based on main; because sonnet-vpsbench's version of `bench_panel.py` differs, an incremental diff against it is in the raw directory (`bench_panel_external_on_vpsbench.diff`; the merged file that actually ran is `merged-scripts/scripts/bench_panel.py`). Apply the vpsbench worktree first.

## Limitations and comparison caveat

- Not certified: shared VPS, clients over the internet (RTT about 100 ms, one outlier of 1.6 s), console-sampled `/perf`, 2 panel samples, one jar, one window.
- SoulFire speaks as a real 26.3 vanilla client through ViaFabricPlus to protocol 776; bench_panel.py's `baseline_client` is a minimal protocol client that follows the workload (teleports to sites, roaming, creative flight). Their loads differ in packets, chunk requests (render distance 2 vs the plan's roam) and entity interaction, so SoulFire numbers must not be compared with `bench_panel.py` numbers, and SoulFire bots do not apply the workload's `site_coordinates` placement by themselves (bench_panel still teleports Bench000.. by console).
- Loopback smoke: fresh world, easy difficulty with zombies, CPU shared; Docker services running (idle). The VPS had no other agent load only in part: sonnet-vpsbench's bench_panel ran during parts of Phase A (a Python client process was present at the start of the 100-bot step).
- SoulFire needs about 4 cores for 3 minutes at start-up; a 1 GB download, 1.4 GB state and an AGPL licence.
- Not tested: more than 88 bots, auto-reconnect behaviour, SoulFire plugins (anti-AFK/chat), online-mode accounts, the players-50 collapse seen with the Python clients (sonnet-vpsbench: server stops ticking near 48 clients).

Raw output: `/Volumes/Ext/DevCache/agent-tmp/sourbycraft/soulfire-2026-10-21/` (`raw-s*.jsonl`, `raw-s*-sf.log`, `vps-raw/`: server console logs, gc.log, panel bench.json, OS samples, failed attempts).
