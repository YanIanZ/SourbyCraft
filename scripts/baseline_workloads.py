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

GROUND_Y = -60  # Superflat: bedrock at -64, dirt, grass at -61, so entities stand at -60.

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
    moving_window: bool = False           # Steady phase advances to fresh terrain each step.
    network_clients: int = 0
    network_rate_per_second: int = 0
    requires_connected_players: bool = False
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


def _populate(centre_x, centre_z, mix, items):
    """Summon one site's entity mix at the centre of a chunk."""
    x, z = centre_x * 16 + 8, centre_z * 16 + 8
    commands = [f"summon {entity} {x} {GROUND_Y} {z}"
                for entity, count in mix for _ in range(count)]
    commands += [f"summon minecraft:item {x} {GROUND_Y + 1} {z} {ITEM_NBT}" for _ in range(items)]
    return commands


def idle():
    return Plan(
        name="idle", level_type="minecraft:flat", minimum_heap_mib=2048,
        summary="Zero load: one forceloaded spawn chunk, no entities, no connections.",
        fidelity=("Establishes the fixed cost of the runtime, telemetry and region scheduler.",
                  "Does not exercise entity, chunk generation or network paths."),
        setup=DETERMINISM + ("forceload add 0 0",))


def players(count, radius=2, gap=2):
    """N spatially distributed ticking clusters, each with a survival entity mix.

    This is a spatial-distribution model, not a player simulation. It reproduces the
    chunk residency and entity population that N dispersed players would create, and
    reproduces neither their network traffic, their entity-tracking cost, nor their
    chunk-streaming cost as they move.
    """
    spacing = 2 * radius + 1 + gap
    sites = list(_grid(count, spacing))
    setup = list(DETERMINISM)
    for centre_x, centre_z in sites:
        setup.append(_forceload(centre_x, centre_z, radius))
    for centre_x, centre_z in sites:
        setup.extend(_populate(centre_x, centre_z, SITE_MIX, SITE_ITEMS))
    per_site = sum(count for _, count in SITE_MIX) + SITE_ITEMS
    chunks = count * (2 * radius + 1) ** 2
    return Plan(
        name=f"players-{count}", level_type="minecraft:flat",
        minimum_heap_mib=max(2048, 1024 + chunks // 2),
        summary=f"{count} dispersed ticking clusters: {chunks} forceloaded chunks, "
                f"{count * per_site} entities.",
        fidelity=("Models chunk residency and entity population for dispersed players.",
                  "Does not model player network traffic, entity tracking or chunk streaming.",
                  "Mob AI is inactive without connected players; see requires_connected_players."),
        setup=tuple(setup), requires_connected_players=True,
        parameters={"sites": count, "chunk_radius": radius, "site_spacing_chunks": spacing,
                    "forceloaded_chunks": chunks, "entities_per_site": per_site,
                    "entities_total": count * per_site})


def entity_stress(mobs=3000, items=3000, radius=4):
    """A dense entity population in one region: tick, collision, merge and despawn load."""
    setup = list(DETERMINISM) + [_forceload(0, 0, radius)]
    span = radius * 16
    for index in range(mobs):
        entity = ("minecraft:zombie", "minecraft:skeleton", "minecraft:cow")[index % 3]
        x, z = -span + (index * 7) % (2 * span), -span + (index * 11) % (2 * span)
        setup.append(f"summon {entity} {x} {GROUND_Y} {z}")
    for index in range(items):
        x, z = -span + (index * 13) % (2 * span), -span + (index * 5) % (2 * span)
        setup.append(f"summon minecraft:item {x} {GROUND_Y + 1} {z} {ITEM_NBT}")
    return Plan(
        name="entity-stress", level_type="minecraft:flat",
        minimum_heap_mib=4096,
        summary=f"{mobs} mobs and {items} item entities inside {(2 * radius + 1) ** 2} chunks.",
        fidelity=("Exercises entity tick, collision, item merge and despawn checks.",
                  "Mob AI, pathfinding and goal selection stay inactive without connected "
                  "players, so this measures the inactive entity path unless clients attach."),
        setup=tuple(setup), requires_connected_players=True,
        parameters={"mobs": mobs, "items": items, "chunk_radius": radius})


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


def network_stress(clients=64, rate_per_second=400):
    """Connection, handshake and status round-trips against the live Netty pipeline."""
    return Plan(
        name="network-stress", level_type="minecraft:flat", minimum_heap_mib=2048,
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
            "chunk-stress": chunk_stress, "network-stress": network_stress}

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
