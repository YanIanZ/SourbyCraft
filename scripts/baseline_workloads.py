#!/usr/bin/env python3
"""The PRD section 9 baseline workloads, expressed as console-driven server load.

Each workload states what it actually exercises. The load is generated with vanilla
commands and, for the network workload, raw client sockets; no workload changes a
performance-related server setting, which PRD section 5 forbids.

The load model deliberately does not claim to simulate players. Server-side mob AI
is gated by entity activation range, which is computed around connected players, so
a run with zero connected players exercises the inactive entity path. Workloads that
depend on that gate declare ``requires_connected_players`` and the runner refuses to
certify their results unless the operator asserts that clients were attached.
"""
from dataclasses import dataclass, field
import math

# Entities are placed on the generated surface rather than at a fixed height. A superflat
# world has a known ground level but is representative of nothing: real terrain changes
# chunk generation cost, block variety and therefore random-tick load, lighting, heightmaps
# and collision shapes. "positioned over world_surface" resolves the height per column.
SURFACE = "positioned over world_surface"
# Recognised by run_baseline.Server.apply as "pause here", never sent to the server.
SETTLE_TOKEN = "@settle"

# Keeping sites in separate regions, derived from ThreadedRegionizer rather than guessed.
#
# Region sections are 1 << grid-exponent chunks square and paper-global.yml ships
# grid-exponent 4, so 16. On chunk load the regionizer creates empty neighbour sections
# within emptySectionCreateRadius (1), then merges any region whose section lies within
# createRadius + regionSectionMergeRadius (2) sections. A site's chunk square can also
# straddle a section boundary, so it occupies up to 2 sections.
#
#   site sections                     2
#   + empty neighbours either side  + 2   (createRadius on each side)
#   + merge reach                   + 1   (regionSectionMergeRadius)
#   + one clear section             + 1
#   = 6 sections minimum
#
# A first attempt used 3 sections (48 chunks) on the assumption that only the merge
# radius mattered. Every site still merged; the create radius and the straddle are what
# it missed. 8 sections is the derived minimum plus margin.
REGION_SECTION_CHUNKS = 16
REGION_SECTIONS_MINIMUM_GAP = 6
REGION_SECTIONS_BETWEEN_SITES = 8
SITE_SPACING_CHUNKS = REGION_SECTION_CHUNKS * REGION_SECTIONS_BETWEEN_SITES

ITEM_NBT = '{Item:{id:"minecraft:cobblestone",count:16}}'

# One player-equivalent site: a survival-server entity mix in one forceloaded cluster.
SITE_MIX = (("minecraft:zombie", 8), ("minecraft:skeleton", 4), ("minecraft:cow", 2),
            ("minecraft:sheep", 2), ("minecraft:villager", 2))
SITE_ITEMS = 12

# Gamerule identifiers are Minecraft-version specific; 26.2 uses snake_case names.
# run_baseline.py fails the run if the server rejects any of these.
DETERMINISM = ("gamerule spawn_mobs false", "gamerule advance_weather false",
               "gamerule advance_time false", "gamerule random_tick_speed 3",
               "time set noon", "weather clear", "difficulty normal")


@dataclass(frozen=True)
class Plan:
    """A reproducible workload: how to configure the world, load it, and hold it."""
    name: str
    summary: str
    level_type: str
    minimum_heap_mib: int
    fidelity: tuple
    setup: tuple = ()
    steady: tuple = ()                    # Re-issued once per steady_interval_seconds.
    steady_interval_seconds: int = 0
    settle_seconds: int = 15              # Quiet time after setup, before warmup.
    moving_window: bool = False           # Steady phase advances to fresh terrain each step.
    network_clients: int = 0
    network_rate_per_second: int = 0
    requires_connected_players: bool = False
    # How far connected clients may wander, or None to explore freely. A workload that measures
    # entities keeps its players beside them: clients flying outward generate terrain for the
    # whole run, and that generation then dominates the profile of what was being measured.
    client_roam_blocks: int | None = None
    parameters: dict = field(default_factory=dict)


def _grid(count, spacing_chunks):
    """Lay ``count`` sites on a square grid centred on the origin, in chunk coordinates."""
    columns = math.ceil(math.sqrt(count))
    offset = (columns - 1) / 2.0
    for index in range(count):
        column, row = index % columns, index // columns
        yield round((column - offset) * spacing_chunks), round((row - offset) * spacing_chunks)


def _forceload(centre_x, centre_z, radius):
    """Forceload the (2*radius+1)^2 chunk square around a chunk coordinate."""
    low_x, low_z = (centre_x - radius) * 16, (centre_z - radius) * 16
    high_x, high_z = (centre_x + radius) * 16 + 15, (centre_z + radius) * 16 + 15
    return f"forceload add {low_x} {low_z} {high_x} {high_z}"


def await_chunks(chunks):
    """Wait for forceloaded terrain to exist before anything is placed on it.

    ``forceload add`` returns as soon as the chunks are marked; generating them happens
    afterwards on the chunk workers. A ``summon`` issued in between is rejected with
    "That position is not loaded" and the entity is silently never created, so the
    workload measures bare terrain while claiming an entity population. Scale the wait
    with the amount of terrain, and cap it so a large workload cannot stall forever --
    if the wait is still short, the summons fail loudly rather than silently, because
    the marker is in run_baseline.COMMAND_FAILURES.
    """
    return f"{SETTLE_TOKEN} {max(30, min(300, chunks // 4))}"


def _at_surface(x, z, command):
    """Run a command at the generated surface height of one column."""
    return f"execute positioned {x} 0 {z} {SURFACE} run {command}"


def _populate(centre_x, centre_z, mix, items):
    """Summon one site's entity mix on the surface at the centre of a chunk."""
    x, z = centre_x * 16 + 8, centre_z * 16 + 8
    commands = [_at_surface(x, z, f"summon {entity} ~ ~ ~")
                for entity, count in mix for _ in range(count)]
    commands += [_at_surface(x, z, f"summon minecraft:item ~ ~1 ~ {ITEM_NBT}") for _ in range(items)]
    return commands


def idle():
    return Plan(
        name="idle", level_type="minecraft:normal", minimum_heap_mib=2048,
        summary="Zero load: one forceloaded spawn chunk, no entities, no connections.",
        fidelity=("Establishes the fixed cost of the runtime, telemetry and region scheduler.",
                  "Does not exercise entity, chunk generation or network paths.",
                  "Runs on generated terrain, not superflat, so the spawn chunk is representative."),
        setup=DETERMINISM + ("forceload add 0 0",))


def players(count, radius=2, spacing=SITE_SPACING_CHUNKS):
    """N spatially distributed ticking clusters, each with a survival entity mix.

    This is a spatial-distribution model, not a player simulation. It reproduces the
    chunk residency and entity population that N dispersed players would create, and
    reproduces neither their network traffic, their entity-tracking cost, nor their
    chunk-streaming cost as they move.
    """
    minimum = REGION_SECTION_CHUNKS * REGION_SECTIONS_MINIMUM_GAP
    if spacing < minimum:
        raise ValueError(f"site spacing {spacing} would merge neighbouring regions; "
                         f"need at least {minimum} chunks (see the derivation above)")
    sites = list(_grid(count, spacing))
    setup = list(DETERMINISM)
    for centre_x, centre_z in sites:
        setup.append(_forceload(centre_x, centre_z, radius))
    per_site = sum(count for _, count in SITE_MIX) + SITE_ITEMS
    chunks = count * (2 * radius + 1) ** 2
    setup.append(await_chunks(chunks))
    for centre_x, centre_z in sites:
        setup.extend(_populate(centre_x, centre_z, SITE_MIX, SITE_ITEMS))
    return Plan(
        name=f"players-{count}", level_type="minecraft:normal",
        minimum_heap_mib=max(2048, 1024 + chunks // 2),
        summary=f"{count} dispersed ticking clusters: {chunks} forceloaded chunks, "
                f"{count * per_site} entities.",
        fidelity=("Runs on generated terrain; seed a pre-generated world with --world so "
                  "terrain generation does not land inside the measurement window.",
                  f"Sites are {spacing} chunks apart so each stays its own region; check "
                  "active_regions in the result, because one region means the run measured "
                  "a single region thread rather than region parallelism.",
                  "Models chunk residency and entity population for dispersed players.",
                  "Does not model player network traffic, entity tracking or chunk streaming.",
                  "Mob AI is inactive without connected players; see requires_connected_players."),
        setup=tuple(setup), requires_connected_players=True,
        settle_seconds=max(60, chunks // 10),
        parameters={"sites": count, "chunk_radius": radius, "site_spacing_chunks": spacing,
                    "region_section_chunks": REGION_SECTION_CHUNKS,
                    "expected_min_regions": count,
                    "forceloaded_chunks": chunks, "entities_per_site": per_site,
                    "entities_total": count * per_site,
                    "site_coordinates": [[cx * 16 + 8, cz * 16 + 8] for cx, cz in sites]})


def entity_stress(mobs=3000, items=3000, radius=4):
    """A dense entity population in one region: tick, collision, merge and despawn load."""
    setup = list(DETERMINISM) + [_forceload(0, 0, radius),
                                 await_chunks((2 * radius + 1) ** 2)]
    span = radius * 16
    for index in range(mobs):
        entity = ("minecraft:zombie", "minecraft:skeleton", "minecraft:cow")[index % 3]
        x, z = -span + (index * 7) % (2 * span), -span + (index * 11) % (2 * span)
        setup.append(_at_surface(x, z, f"summon {entity} ~ ~ ~"))
    for index in range(items):
        x, z = -span + (index * 13) % (2 * span), -span + (index * 5) % (2 * span)
        setup.append(_at_surface(x, z, f"summon minecraft:item ~ ~1 ~ {ITEM_NBT}"))
    return Plan(
        name="entity-stress", level_type="minecraft:normal",
        minimum_heap_mib=4096,
        summary=f"{mobs} mobs and {items} item entities inside {(2 * radius + 1) ** 2} chunks.",
        fidelity=("Runs on generated terrain; entities are placed on the surface per column.",
                  "Exercises entity tick, collision, item merge and despawn checks.",
                  "Mob AI, pathfinding and goal selection stay inactive without connected "
                  "players, so this measures the inactive entity path unless clients attach."),
        setup=tuple(setup), requires_connected_players=True, settle_seconds=60,
        # The entities sit inside radius*16 blocks of the origin; the players stay with them.
        client_roam_blocks=radius * 16,
        parameters={"mobs": mobs, "items": items, "chunk_radius": radius,
                    "client_roam_blocks": radius * 16})


def chunk_stress(radius=4, step_chunks=64, interval_seconds=10):
    """Continuous generation, load and unload of previously ungenerated terrain."""
    return Plan(
        name="chunk-stress", level_type="minecraft:normal", minimum_heap_mib=4096,
        summary="Forceloads a fresh terrain window every "
                f"{interval_seconds}s, releasing the previous one.",
        fidelity=("Exercises chunk generation, load, integration, unload and save.",
                  "Generation dominates; steady-state lookup cost is better measured by "
                  "the players-N workloads.",
                  "Chunk request and generation latency are not directly instrumented; "
                  "read them from the JFR recording."),
        setup=tuple(DETERMINISM), steady=("forceload remove all", "@window", "save-all"),
        steady_interval_seconds=interval_seconds, moving_window=True,
        parameters={"chunk_radius": radius, "step_chunks": step_chunks,
                    "chunks_per_window": (2 * radius + 1) ** 2})


def save_stress(radius=6, churn_columns=192, interval_seconds=15):
    """Dirty many chunks, then force them to disk, repeatedly.

    §16 lists save stress separately from chunk traversal, and the difference is real:
    chunk-stress measures generating and loading new terrain, while this measures writing
    *already loaded* chunks that keep changing. The save path is the one place §11.6's rule
    bites -- no performance gain may come from silently weakening durability -- so it needs a
    workload that makes the server write, not one that happens to call save-all.

    No connected players are required: block edits and region writes have no activation gate.
    """
    chunks = (2 * radius + 1) ** 2
    span = radius * 16
    setup = [*DETERMINISM,
             f"forceload add -{span} -{span} {span} {span}",
             await_chunks(chunks)]
    # Churn spread across the whole forceloaded square rather than one column, so the dirty
    # set is many region-file sectors instead of the same one rewritten.
    churn = []
    for index in range(churn_columns):
        x = -span + (index * 17) % (2 * span)
        z = -span + (index * 23) % (2 * span)
        # Alternating blocks so every pass genuinely changes state; writing the same block
        # back would leave the chunk clean and the save path idle.
        block = ("minecraft:stone", "minecraft:sandstone")[index % 2]
        churn.append(_at_surface(x, z, f"fill ~ ~1 ~ ~3 ~4 ~3 {block}"))
    return Plan(
        name="save-stress", level_type="minecraft:normal", minimum_heap_mib=4096,
        summary=f"Rewrites {churn_columns} columns across {chunks} forceloaded chunks, "
                f"flushing every {interval_seconds}s.",
        fidelity=("Exercises the save queue, chunk serialization and region-file writes.",
                  "Measures writing loaded chunks that keep changing, not generating new "
                  "terrain -- chunk-stress covers generation.",
                  "Storage backlog is not instrumented yet, so queue depth on the save path "
                  "is not visible in the snapshot; read throughput from the JFR recording.",
                  "No connected players are needed: block edits and region writes have no "
                  "activation gate."),
        setup=tuple(setup), steady=(*churn, "save-all flush"),
        steady_interval_seconds=interval_seconds, settle_seconds=30,
        parameters={"chunk_radius": radius, "chunks": chunks,
                    "churn_columns": churn_columns, "blocks_per_pass": churn_columns * 4 * 4 * 4})


def network_stress(clients=64, rate_per_second=400):
    """Connection, handshake and status round-trips against the live Netty pipeline."""
    return Plan(
        name="network-stress", level_type="minecraft:normal", minimum_heap_mib=2048,
        summary=f"{clients} concurrent clients driving up to {rate_per_second} "
                "handshake/status round-trips per second.",
        fidelity=("Exercises accept, decode, encode, flush and connection teardown.",
                  "Status-protocol only: it does not exercise play-state packets, chunk "
                  "packet generation, entity tracking packets or compression of large payloads."),
        setup=DETERMINISM + ("forceload add 0 0",),
        network_clients=clients, network_rate_per_second=rate_per_second,
        parameters={"protocol_phase": "handshake+status+ping"})


BUILDERS = {"idle": idle,
            "players-10": lambda: players(10), "players-50": lambda: players(50),
            "players-100": lambda: players(100), "entity-stress": entity_stress,
            "chunk-stress": chunk_stress, "save-stress": save_stress,
            "network-stress": network_stress}

NAMES = tuple(BUILDERS)


def build(name):
    if name not in BUILDERS:
        raise KeyError(f"Unknown workload {name!r}; known: {', '.join(NAMES)}")
    return BUILDERS[name]()


def window_commands(plan, step):
    """The forceload command for one step of a moving-window workload."""
    if not plan.moving_window:
        raise ValueError(f"{plan.name} has no moving window")
    radius = plan.parameters["chunk_radius"]
    centre = (step + 1) * plan.parameters["step_chunks"]
    return _forceload(centre, centre, radius)
