"""Crash recovery on Sourby Demo: write a probe, flush, SIGKILL the server through the panel, boot,
and read the probe back. Second cycle: kill while block writes are in flight without a flush —
the last flushed checkpoint must hold and nothing may be corrupt. Reuses verify_persistence_panel's
probe helpers and constants."""
import re, sys, time
sys.path.insert(0, str(__import__("pathlib").Path(__file__).resolve().parent))
import verify_persistence_panel as vp
panel = vp.panel
import argparse
_a = argparse.ArgumentParser(description=__doc__); _a.add_argument("--panel", required=True); _a.add_argument("--server", required=True)
_args = _a.parse_args(); panel.configure(_args.panel, _args.server)
results = vp.results
record = vp.record

def players():
    out, _ = panel.run("minecraft:list", r"players online", 20)
    m = re.search(r"There are (\d+) ", out); return int(m.group(1)) if m else -1

def summon(n):
    for i in range(n):
        panel.run(f"summon minecraft:armor_stand {vp.X + i % vp.SIDE} {vp.Y + 2} {vp.Z + i // vp.SIDE} "
                  f"{{Tags:[\"{vp.TAG}\"],NoGravity:1b,Invulnerable:1b}}", r"Summoned|Unable|rror", 20)

def kill_and_boot(label):
    panel.power("kill")
    stopped = panel.wait_state("offline", 300)
    record(f"{label}: process killed", stopped)
    panel.power("start")
    running = panel.wait_state("running", 600)
    text = panel.log()
    record(f"{label}: boot reached Done after kill", running and bool(re.search(r"Done \(\d", text)))
    bad = [l[-140:] for l in text.splitlines() if re.search(r"corrupt|Corrupt|Failed to load chunk|Exception in thread|ERROR\]", l)]
    record(f"{label}: no corruption/ERROR at boot", not bad, bad[:3])

if players() != 0: raise SystemExit("players online; refusing")
try:
    vp.forceload()
    n = vp.fill("minecraft:diamond_block"); record("probe blocks written", n == vp.SIDE * vp.SIDE, f"{n}")
    # Entities of a freshly force-loaded chunk appear a few ticks after its blocks; a kill that runs
    # before that matches nothing and the previous run's probes double the count.
    for _ in range(10):
        panel.run(f"kill @e[tag={vp.TAG}]", r"Killed|No entity", 20); time.sleep(2)
        if vp.probe_count() == 0: break
    summon(24); time.sleep(2); record("probe entities written", vp.probe_count() == 24, vp.probe_count())
    out, _ = panel.run("save-all flush", r"Saved the game", 120); record("save-all flush acknowledged", "Saved the game" in out)
    t0 = vp.gametime(); time.sleep(3)
    kill_and_boot("cycle 1 (after flush)")
    vp.forceload()
    blocks = vp.fill("minecraft:sponge", "minecraft:diamond_block"); vp.fill("minecraft:diamond_block", "minecraft:sponge")
    record("cycle 1: flushed blocks survived SIGKILL", blocks == vp.SIDE * vp.SIDE, f"{blocks} of {vp.SIDE * vp.SIDE}")
    record("cycle 1: flushed entities survived SIGKILL", vp.probe_count() == 24, vp.probe_count())
    t1 = vp.gametime(); record("cycle 1: game time not behind the flushed value", t1 >= t0, f"{t1} vs {t0}")
    # Cycle 2: writes in flight, no flush. Alternate the probe block every second, then kill mid-write.
    for i in range(6):
        vp.fill("minecraft:gold_block" if i % 2 == 0 else "minecraft:diamond_block"); time.sleep(1)
    kill_and_boot("cycle 2 (writes in flight, no flush)")
    vp.forceload()
    gold = vp.fill("minecraft:sponge", "minecraft:gold_block"); vp.fill("minecraft:gold_block", "minecraft:sponge")
    dia = vp.fill("minecraft:sponge", "minecraft:diamond_block"); vp.fill("minecraft:diamond_block", "minecraft:sponge")
    record("cycle 2: probe area is one consistent checkpoint (all gold or all diamond)",
           (gold == vp.SIDE * vp.SIDE and dia == 0) or (dia == vp.SIDE * vp.SIDE and gold == 0), f"gold {gold}, diamond {dia}")
    record("cycle 2: entities from the last checkpoint present", vp.probe_count() == 24, vp.probe_count())
finally:
    panel.run(f"kill @e[tag={vp.TAG}]", r"Killed|No entity", 20)
    vp.fill("minecraft:air", "minecraft:diamond_block"); vp.fill("minecraft:air", "minecraft:gold_block"); vp.fill("minecraft:air", "minecraft:sponge")
    panel.run(f"forceload remove {vp.X} {vp.Z} {vp.X + vp.SIDE} {vp.Z + vp.SIDE}", r"[Uu]nmarked|not|No", 30)
    record("cleanup: no probe entities left", vp.probe_count() == 0, vp.probe_count())
    print("PASSED" if all(ok for _, ok in results) else f"FAILED {sum(1 for _, ok in results if not ok)} of {len(results)}")
