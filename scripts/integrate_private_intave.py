#!/usr/bin/env python3
"""Port the verified private workspace to the native server lifecycle; never activates a server."""
from __future__ import annotations
import argparse
import hashlib
import json
import re
import tempfile
from pathlib import Path

from private_intave_workspace import ROOT, inspect, read_plan, relocate, verify_sources, write_json
from private_intave_inventory import modernize_inventory
from private_intave_block import modernize_fallback_block


JAVA_NON_CODE = re.compile(
    r'//[^\r\n]*|/\*.*?\*/|"""(?:\\.|(?!""").)*"""|"(?:\\.|[^"\\])*"|\'(?:\\.|[^\'\\])*\'',
    re.DOTALL,
)


def replace_method(source: str, marker: str, replacement: str) -> str:
    # Keep offsets, but ignore braces/signatures inside Java literals and comments.
    code = JAVA_NON_CODE.sub(lambda match: " " * len(match.group()), source)
    matches = list(re.finditer(re.escape(marker), code))
    if len(matches) != 1:
        raise ValueError(f"Expected exactly one native transformation target: {marker}")
    start = matches[0].start()
    opening = code.find("{", matches[0].end())
    if opening < 0:
        raise ValueError(f"Native transformation target has no body: {marker}")
    depth = 1
    end = opening + 1
    while depth and end < len(code):
        if code[end] == "{": depth += 1
        elif code[end] == "}": depth -= 1
        end += 1
    if depth:
        raise ValueError(f"Unbalanced native transformation target: {marker}")
    return source[:start] + replacement + source[end:]


def replace_code(source: str, replacements: dict[str, str]) -> str:
    """Replace Java code tokens only; keep comments, literals and license notices byte-exact."""
    pattern = re.compile(r"(?<![\w$])(?:" + "|".join(re.escape(key) for key in replacements) + r")(?![\w$])")
    pieces = []
    start = 0
    for token in JAVA_NON_CODE.finditer(source):
        pieces.append(pattern.sub(lambda match: replacements[match.group()], source[start:token.start()]))
        pieces.append(token.group())
        start = token.end()
    pieces.append(pattern.sub(lambda match: replacements[match.group()], source[start:]))
    return "".join(pieces)


def plan_native_port(workspace: Path, plan: dict) -> tuple[dict[Path, str], dict]:
    """Render the entire transformation before changing any source file."""
    main = workspace / "src/main/java"
    # Validate required entrypoints before editing any baseline file.
    original_entry = main / "dev/yanianz/intave/IntavePlugin.java"
    original_source = original_entry.read_text()
    for marker in ("  public void redirectPluginLogger()", "  public File dataFolder()", "      StartupTasks.runAll();",
                   "  public IntaveAccess access() {", "extends JavaPlugin", "    stage2();\n"):
        if marker not in original_source:
            raise ValueError(f"Pinned entrypoint does not match native port transformation: {marker}")
    for required in ("module/Requirements.java", "adapter/ProtocolLibraryAdapter.java", "packet/PacketSender.java",
                     "command/stages/DiagnosticsStage.java"):
        if not (main / "dev/yanianz/intave" / required).is_file():
            raise ValueError(f"Missing native integration target: {required}")
    updates = {}
    removed = []

    def source_text(path: Path) -> str:
        return updates[path] if path in updates else path.read_text()

    for path in list(main.rglob("*.java")) + list((workspace / "src/test/java").rglob("*.java")):
        original = path.read_text()
        changed = re.sub(r"\bIntavePlugin\b", "IntaveEngine", original)
        changed = replace_code(changed, {
            "Attribute.GENERIC_ATTACK_DAMAGE": "Attribute.ATTACK_DAMAGE",
            "GENERIC_MOVEMENT_SPEED": "MOVEMENT_SPEED",
            "PotionEffectType.SLOW": "PotionEffectType.SLOWNESS",
            "PotionEffectType.JUMP": "PotionEffectType.JUMP_BOOST",
            "PotionEffectType.DAMAGE_RESISTANCE": "PotionEffectType.RESISTANCE",
            "Enchantment.DAMAGE_ALL": "Enchantment.SHARPNESS",
            "Enchantment.DAMAGE_ARTHROPODS": "Enchantment.BANE_OF_ARTHROPODS",
            "Enchantment.DAMAGE_UNDEAD": "Enchantment.SMITE",
            "Enchantment.DURABILITY": "Enchantment.UNBREAKING",
            "Enchantment.PROTECTION_ENVIRONMENTAL": "Enchantment.PROTECTION",
            "EntityType.PIG_ZOMBIE": "EntityType.ZOMBIFIED_PIGLIN",
            "Material.COBBLE_WALL": "Material.COBBLESTONE_WALL",
            "Material.GRASS": "Material.GRASS_BLOCK",
            "Particle.VILLAGER_HAPPY": "Particle.HAPPY_VILLAGER",
        })
        relative = path.relative_to(main).as_posix() if path.is_relative_to(main) else ""
        if relative == "dev/yanianz/intave/player/fake/FakePlayerBody.java":
            changed = replace_code(changed, {"case GRASS": "case GRASS_BLOCK", "case WOOD": "case OAK_PLANKS"})
        if relative == "dev/yanianz/intave/module/linker/packet/tinyprotocol/TinyProtocol.java":
            changed = changed.replace("((GameProfile) FROM_PACKET.invoke(packet)).getName()",
                                      "((GameProfile) FROM_PACKET.invoke(packet)).name()")
        if relative == "dev/yanianz/intave/block/physics/MaterialMagic.java":
            for name, query in (("blocksMovement", "blocksMotion"), ("blockSolid", "isSolid")):
                changed = replace_method(changed, "  public static boolean " + name + "(Material material)",
                    "  public static boolean " + name + "(Material material) {\n"
                    "    return dev.yanianz.intave.integration.NativeBlockStates.defaultState(material)." + query + "();\n  }")
            for name in ("includesMaterialLogic", "includesMaterialTransparent"):
                changed = replace_method(changed, "  private static boolean " + name + "(Material material)", "")
        if relative == "dev/yanianz/intave/world/Particles.java":
            changed = changed.replace("player.playEffect(position.toLocation(world), Effect.HAPPY_VILLAGER, 0);",
                                      "player.spawnParticle(Particle.HAPPY_VILLAGER, position.toLocation(world), 1);")
        if relative == "dev/yanianz/intave/block/fluid/FluidTests.java":
            start = changed.index("    if (MinecraftVersions.VER1_13_0.atOrAbove()) {", changed.index("  public void testWaterLevel()"))
            end = changed.index("    Player player =", start)
            changed = changed[:start] + ("    org.bukkit.block.data.Levelled data = (org.bukkit.block.data.Levelled) block.getBlockData();\n"
                "    data.setLevel(3);\n    block.setBlockData(data, false);\n\n") + changed[end:]
        if "JavaPlugin intave = IntaveEngine.singletonInstance();" in changed:
            changed = changed.replace("JavaPlugin intave = IntaveEngine.singletonInstance();", "org.bukkit.plugin.Plugin intave = IntaveEngine.singletonInstance();")
        if path.relative_to(workspace).as_posix() == "src/main/java/dev/yanianz/intave/user/meta/MovementMetadata.java":
            # Modern Bukkit also declares Input; the simulation uses Intave's own value type.
            changed = changed.replace("import dev.yanianz.intave.share.*;",
                                      "import dev.yanianz.intave.share.*;\nimport dev.yanianz.intave.share.Input;")
        if path.relative_to(workspace).as_posix() == "src/main/java/dev/yanianz/intave/test/MockEmptyInventory.java":
            changed = modernize_inventory(changed, replace_method)
        if path.relative_to(workspace).as_posix() == "src/main/java/dev/yanianz/intave/block/access/FakeFallbackBlock.java":
            changed = modernize_fallback_block(changed, replace_method)
        if changed != original:
            if path.name == "IntavePlugin.java":
                removed.append(str(path.relative_to(workspace)))
                path = path.with_name("IntaveEngine.java")
                if path.exists():
                    raise ValueError("Existing native entrypoint preserved; rename would overwrite it")
            updates[path] = changed

    entry = main / "dev/yanianz/intave/IntaveEngine.java"
    source = source_text(entry)
    source = source.replace("  private final AtomicBoolean shutdownStarted", "  private final java.util.concurrent.CompletableFuture<Void> nativeInitialization = new java.util.concurrent.CompletableFuture<>();\n  private final AtomicBoolean shutdownStarted")
    source = source.replace("import org.bukkit.plugin.java.JavaPlugin;\n", "")
    source = source.replace("extends JavaPlugin", "extends dev.yanianz.intave.integration.NativeService")
    source = source.replace("    stage2();\n", "    // Server provider invokes stage2 after assigning the owner for failure cleanup.\n", 1)
    source = source.replace("    Libraries.setupLibraries(message -> logger.info(message));", "    // Native runtime dependencies are bundled/resolved by the server build, never injected at boot.")
    source = source.replace("      ComponentLoader componentLoader = new ComponentLoader(this);\n      componentLoader.prepareComponents();\n      componentLoader.loadComponents();", "      // Server-owned packet API replaces dependency plugin provisioning.")
    source = source.replace("      metrics = new Metrics(this, 6019);", "      // Private native build does not start external plugin telemetry.")
    source = replace_method(source, "  public void redirectPluginLogger()", "  public void redirectPluginLogger() { /* Native logger has no JavaPlugin field to inject. */ }")
    source = replace_method(source, "  public File dataFolder()", "  public File dataFolder() { return getDataFolder(); }")
    source = source.replace("      versions.setup();", "      // No remote version-index lookup in the private native lifecycle.")
    source = source.replace("    displayVersionInformation();", "    logger.info(\"Private native Intave; upstream c67af7f4, effectiveness unverified\");")
    source = source.replace('    randomExitMessages = Resources.localServiceCacheResource("exitmessages", "exitmessages", TimeUnit.DAYS.toMillis(7)).readLines();', "    randomExitMessages = java.util.List.of();")
    # Do not sweep global temporary files/shared plugin caches from a native service.
    source = source.replace("    BackgroundExecutors.executeWhenever(this::clearIntegrityGarbage);", "")
    source = source.replace("    BackgroundExecutors.executeWhenever(this::clearSaveFolderGarbage);", "")
    source = source.replace("    clearIntegrityGarbage();", "    // Native service never sweeps shared plugin/temp caches.")
    source = source.replace("      try {\n        cloud.connectMasterShard();", "      try {\n        if (Boolean.getBoolean(\"sourbycraft.intave.cloud\")) cloud.connectMasterShard();")
    source = source.replace("    logger.info(\"Intave booted successfully\");", "    logger.info(\"Native setup finished; final stage pending\");")
    source = source.replace("      // stage 11\n      Modules.proceedBoot(BootSegment.STAGE_11);\n\n      StartupTasks.runAll();", "      try {\n        Modules.proceedBoot(BootSegment.STAGE_11);\n        StartupTasks.runAll();\n        nativeInitialization.complete(null);\n      } catch (RuntimeException | LinkageError failure) {\n        nativeInitialization.completeExceptionally(failure);\n        throw failure;\n      }")
    source = source.replace("  public IntaveAccess access() {", "  public java.util.concurrent.CompletionStage<Void> nativeInitialization() {\n    if (shutdownStarted.get()) nativeInitialization.completeExceptionally(new IllegalStateException(\"Engine shut down during initialization\"));\n    return nativeInitialization;\n  }\n\n  public boolean nativeReady() { return successfullyBooted && !shutdownStarted.get() && nativeInitialization.isDone() && !nativeInitialization.isCompletedExceptionally(); }\n\n  @Override public org.bukkit.configuration.file.FileConfiguration getConfig() {\n    return configService == null ? super.getConfig() : configService.configuration();\n  }\n\n  public IntaveAccess access() {")
    updates[entry] = source

    requirements = main / "dev/yanianz/intave/module/Requirements.java"
    source = source_text(requirements).replace('return requiresPlugin("ProtocolLib").and(() -> ProtocolLibrary.getProtocolManager() != null);', 'return () -> ProtocolLibrary.getProtocolManager() != null;')
    source = source.replace('return requiresPlugin("Intave");', 'return () -> IntaveEngine.singletonInstance() != null && IntaveEngine.singletonInstance().isEnabled();')
    source = source.replace("import com.comphenix.protocol.ProtocolLibrary;", "import com.comphenix.protocol.ProtocolLibrary;\nimport dev.yanianz.intave.IntaveEngine;")
    updates[requirements] = source

    adapter = main / "dev/yanianz/intave/adapter/ProtocolLibraryAdapter.java"
    source = source_text(adapter).replace('return Bukkit.getPluginManager().getPlugin("ProtocolLib") != null;', 'return com.comphenix.protocol.ProtocolLibrary.getProtocolManager() != null;')
    updates[adapter] = source
    sender = main / "dev/yanianz/intave/packet/PacketSender.java"
    source = source_text(sender).replace("private static final PacketFilterManager protocolManager = (PacketFilterManager) ProtocolLibrary.getProtocolManager();", "private static final ProtocolManager protocolManager = ProtocolLibrary.getProtocolManager();")
    updates[sender] = source

    # This optional diagnostic reflected PacketFilterManager internals, absent in the native path.
    diagnostics = main / "dev/yanianz/intave/command/stages/DiagnosticsStage.java"
    source = replace_method(source_text(diagnostics), "  public void attackTraceCommand(User user)",
        '  public void attackTraceCommand(User user) {\n'
        '    user.player().sendMessage("Native packet adapter has no ProtocolLib injector trace; use /intave-native status");\n'
        '  }')
    updates[diagnostics] = source

    volatile = main / "dev/yanianz/intave/block/access/VolatileBlockAccess.java"
    if volatile.is_file():
        source = source_text(volatile)
        source = replace_method(source, "  public static Block blockAccess(World blockAccess, int x, int y, int z)",
            "  public static Block blockAccess(World blockAccess, int x, int y, int z) {\n"
            "    new dev.yanianz.intave.integration.NativeBlockView(blockAccess).getBlockState(new net.minecraft.core.BlockPos(x, y, z));\n"
            "    return blockAccess.getBlockAt(x, y, z);\n  }")
        source = replace_method(source, "  private static Block fallbackBlock(World world)", "")
        field = "  private static final Map<World, Block> EMERGENCY_FALLBACK_BLOCKS = GarbageCollector.watch(new HashMap<>());"
        if source.count(field) != 1:
            raise ValueError("Pinned emergency fallback map differs from native migration")
        source = source.replace(field, "")
        for marker, query, coordinates, world in (
            ("  public static @NotNull Material typeAccess(User user, World blockAccess, int blockX, int blockY, int blockZ)",
             "type", "blockX, blockY, blockZ", "blockAccess"),
            ("  public static int variantIndexAccess(User user, World blockAccess, int blockX, int blockY, int blockZ)",
             "variantIndex", "blockX, blockY, blockZ", "blockAccess"),
            ("  public static BlockShape collisionShapeAccess(User user, int x, int y, int z)",
             "collisionShape", "x, y, z", "user.player().getWorld()"),
        ):
            source = replace_method(source, marker, marker + " {\n"
                "    BlockState cached = user.blockCache().peekStateAt(" + coordinates + ");\n"
                "    if (cached != null) return cached." + query + "();\n"
                "    new dev.yanianz.intave.integration.NativeBlockView(" + world + ").getBlockState(new net.minecraft.core.BlockPos(" + coordinates + "));\n"
                "    return user.blockCache()." + query + "At(" + coordinates + ");\n  }")
        source = replace_method(source, "  public static boolean isInLoadedChunk(World world, int x, int z)",
            "  public static boolean isInLoadedChunk(World world, int x, int z) {\n"
            "    return new dev.yanianz.intave.integration.NativeBlockView(world).isLoaded(x, z);\n  }")
        updates[volatile] = source

    # Native mapped adapters must be instantiated directly, never translated a second time.
    native_setup = {
        "block/access/BlockAccess.java": ("  public static void setup()", "    blockAccessor = new v20BlockAccessor();"),
        "block/shape/resolve/DrillResolver.java": ("  public static void serverInit()",
            "    drill = new dev.yanianz.intave.block.shape.resolve.drill.v20ShapeDrill();"),
    }
    for name, (marker, body) in native_setup.items():
        target = main / "dev/yanianz/intave" / name
        if target.is_file():
            updates[target] = replace_method(source_text(target), marker, marker + " {\n" + body + "\n  }")
    for name, assignment in (
        ("block/variant/index/VariantIndex.java", "INDEXER = new ModernIndexer();"),
        ("block/variant/convert/ConversionBridges.java", "conversionBridge = new v16ConversionBridge();"),
    ):
        target = main / "dev/yanianz/intave" / name
        if target.is_file():
            updates[target] = replace_method(source_text(target), "  static", "  static { " + assignment + " }")

    register = main / "dev/yanianz/intave/block/variant/BlockVariantRegister.java"
    if register.is_file():
        updates[register] = replace_method(source_text(register), "  public static Object rawVariantOf(Material type, int variantIndex)",
            "  public static Object rawVariantOf(Material type, int variantIndex) {\n"
            "    Map<Integer, Object> variants = blockDataRegister.get(type);\n"
            "    Object state = variants == null ? null : variants.get(variantIndex);\n"
            "    if (state == null) throw new IllegalStateException(\"Unindexed native variant: \" + type + \":\" + variantIndex);\n"
            "    return state;\n  }")

    # The native server has mapped BlockState/FluidState types already. A legacy classloader
    # rewrite would remap them a second time, and skipped indexing errors could appear as AIR.
    fluids = main / "dev/yanianz/intave/block/fluid/Fluids.java"
    if fluids.is_file():
        source = source_text(fluids)
        start = source.index("    String className;", source.index("  public static void setup()"))
        end = source.index("    for (Material value : Material.values())", start)
        source = source[:start] + "    FluidResolver resolver = new v26FluidResolver();\n\n" + source[end:]
        target = "            exception.printStackTrace();"
        if source.count(target) != 1:
            raise ValueError("Pinned fluid indexing catch does not match native migration")
        source = source.replace(target,
            '            throw new IllegalStateException("Native fluid indexing failed for " + value + ":" + variantIndex, exception);')
        updates[fluids] = source

    templates = ROOT / "scripts/templates/intave-native"
    for template in templates.glob("*.java"):
        text = template.read_text()
        package = re.search(r"^package ([\w.]+);", text).group(1)
        target = main / package.replace(".", "/") / template.name
        if template.name in ("v26FluidResolver.java", "v18b2FluidResolver.java", "v20BlockAccessor.java",
                              "v20ShapeDrill.java", "ModernIndexer.java", "v16ConversionBridge.java",
                              "v14BlockAccessor.java", "v17b1ShapeDrill.java"):
            original = target.read_text()
            if "@PatchyAutoTranslation" not in original:
                raise ValueError("Pinned NMS adapter differs from native migration target")
            # Preserve any upstream preamble/license, including future pin changes.
            text = original[:original.index("package ")] + text
        updates[target] = text
    generated = workspace / "generated/main/java/dev/yanianz/intave/IntaveBuildConfig.java"
    if not generated.is_file():
        raise ValueError("Missing generated build identity; baseline preserved")
    if not updates.get(main / "dev/yanianz/intave/integration/NativeEngineProvider.java"):
        raise ValueError("Missing native provider template; baseline preserved")
    payloads = {path: text.encode("utf-8") for path, text in updates.items()}
    payloads[generated] = generated.read_bytes()
    report = {
        "commit": plan["commit"], "state": "NATIVE_CODE_INTEGRATED_UNVERIFIED",
        "runtime_verified": False, "anticheat_active": False,
        "removed_files": removed,
        "files": [{"path": str(path.relative_to(workspace)), "sha256": hashlib.sha256(data).hexdigest()}
                  for path, data in sorted(payloads.items())],
    }
    return updates, report


def integrate(workspace: Path) -> dict:
    plan = read_plan()
    metadata = json.loads((workspace / "workspace.json").read_text())
    if metadata["commit"] != plan["commit"]:
        raise ValueError("Workspace commit does not match the private native plan")
    check = inspect(workspace, Path("/nonexistent-intave-cache"), plan=plan)
    if check["changed_sources"] or check["changed_local_compile_libraries"]:
        raise ValueError("Private source/library changed; preserved: " + ", ".join(
            check["changed_sources"] + check["changed_local_compile_libraries"]))
    report_path = workspace / "native-port.json"
    if report_path.exists():
        return json.loads(report_path.read_text())
    updates, report = plan_native_port(workspace, plan)
    # All targets, templates and transformations have passed preflight at this point.
    # Publication never runs while still discovering transformation errors.
    paths = set(updates) | {workspace / name for name in report["removed_files"]} | {report_path}
    originals = {path: path.read_bytes() if path.is_file() else None for path in paths}
    touched = []
    try:
        for path, text in updates.items():
            path.parent.mkdir(parents=True, exist_ok=True)
            touched.append(path)
            path.write_bytes(text.encode("utf-8"))
        for name in report["removed_files"]:
            path = workspace / name
            touched.append(path)
            path.unlink()
        for entry in report["files"]:
            path = workspace / entry["path"]
            if not path.is_file() or hashlib.sha256(path.read_bytes()).hexdigest() != entry["sha256"]:
                raise ValueError(f"Written native source differs from rendered port: {entry['path']}")
        touched.append(report_path)
        write_json(report_path, report)
    except (OSError, ValueError):
        # Restore partial writes/deletions on an ordinary I/O error. Do not publish a ledger.
        for path in reversed(touched):
            original = originals[path]
            if original is None:
                path.unlink(missing_ok=True)
            else:
                path.write_bytes(original)
        raise
    return report


def render_from_verified(workspace: Path, plan: dict) -> tuple[dict[str, str], dict]:
    """Render current templates from immutable verified sources, never from the modified port."""
    raw = workspace / "verified-upstream"
    inventory = json.loads((raw / "inventory.json").read_text())
    with tempfile.TemporaryDirectory(prefix="intave-reproduction-") as temporary:
        baseline = Path(temporary)
        for name, data in verify_sources(raw, inventory, plan):
            name, data = relocate(name, data)
            if name in ("build.gradle.kts", "gradle/packaging.gradle.kts"):
                name = "upstream-build/" + name
            target = baseline / name
            target.parent.mkdir(parents=True, exist_ok=True)
            target.write_bytes(data)
        generated = Path("generated/main/java/dev/yanianz/intave/IntaveBuildConfig.java")
        (baseline / generated).parent.mkdir(parents=True, exist_ok=True)
        (baseline / generated).write_bytes((workspace / generated).read_bytes())
        updates, rendered = plan_native_port(baseline, plan)
        sources = {path.relative_to(baseline).as_posix(): source for path, source in updates.items()}
    return sources, rendered


def refresh_port(workspace: Path) -> dict:
    """Explicit template upgrade; reject local edits and roll back ordinary publication failures."""
    if not (workspace / "native-port.json").is_file():
        raise ValueError("Refresh requires an existing verified native port")
    existing = integrate(workspace) # validates current hashes, closed inventory and pin
    sources, rendered = render_from_verified(workspace, read_plan())
    if existing["removed_files"] != rendered["removed_files"]:
        raise ValueError("Refresh cannot alter the removal contract; workspace preserved")
    old_names = {entry["path"] for entry in existing["files"]}
    new_names = {entry["path"] for entry in rendered["files"]}
    if old_names - new_names:
        raise ValueError("Refresh cannot drop existing port inputs; workspace preserved")
    changed = {workspace / name: source.encode("utf-8") for name, source in sources.items()
               if not (workspace / name).is_file() or (workspace / name).read_bytes() != source.encode("utf-8")}
    report_path = workspace / "native-port.json"
    originals = {path: path.read_bytes() if path.is_file() else None for path in [*changed, report_path]}
    touched = []
    try:
        for path, data in changed.items():
            path.parent.mkdir(parents=True, exist_ok=True)
            touched.append(path)
            path.write_bytes(data)
        for entry in rendered["files"]:
            path = workspace / entry["path"]
            if hashlib.sha256(path.read_bytes()).hexdigest() != entry["sha256"]:
                raise ValueError("Refreshed source differs from rendered port: " + entry["path"])
        if rendered != existing:
            touched.append(report_path)
            write_json(report_path, rendered)
    except (OSError, ValueError):
        for path in reversed(touched):
            original = originals[path]
            if original is None: path.unlink(missing_ok=True)
            else: path.write_bytes(original)
        raise
    return {"changed_files": len(changed), "ported_files": len(rendered["files"]),
            "state": rendered["state"], "runtime_verified": False}


def verify_reproducibility(workspace: Path) -> dict:
    """Render again from verified originals, then compare the existing port without overwriting it."""
    plan = read_plan()
    existing = integrate(workspace) if (workspace / "native-port.json").is_file() else None
    if existing is None:
        raise ValueError("Reproducibility verification requires an existing native port")
    _, rendered = render_from_verified(workspace, plan)
    expected = {entry["path"]: entry["sha256"] for entry in existing["files"]}
    actual = {entry["path"]: entry["sha256"] for entry in rendered["files"]}
    differences = sorted(name for name in expected.keys() | actual.keys() if expected.get(name) != actual.get(name))
    if differences or existing["removed_files"] != rendered["removed_files"]:
        raise ValueError("Native port differs from source/template reproduction; preserved: " + ", ".join(differences))
    return {"reproduced_files": len(actual), "commit": plan["commit"],
            "workspace_unchanged": True, "engine_compiled": False, "runtime_verified": False}


def main() -> None:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--verify-reproducibility", action="store_true",
                        help="Render from verified upstream originals and compare without writing the port")
    parser.add_argument("--refresh-port", action="store_true", help="Upgrade verified native sources from current templates; preserve local edits")
    args = parser.parse_args()
    workspace = ROOT / ".private-intave/workspaces" / read_plan()["commit"]
    try:
        if args.refresh_port:
            if args.verify_reproducibility:
                raise ValueError("Refresh and read-only verification cannot be requested together")
            print(json.dumps(refresh_port(workspace), indent=2))
            return
        if args.verify_reproducibility:
            print(json.dumps(verify_reproducibility(workspace), indent=2))
            return
        report = integrate(workspace)
    except (ValueError, OSError, KeyError) as failure:
        raise SystemExit(f"Native Intave integration failed: {failure}") from failure
    print(f"Native source port prepared: {len(report['files'])} files; compile/boot verification pending")


if __name__ == "__main__":
    main()
