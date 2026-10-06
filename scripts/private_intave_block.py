"""Reproducible API additions for Intave's read-only AIR fallback block."""
from pathlib import Path
import re


def modernize_fallback_block(source: str, replace_method) -> str:
    for signature in ("int getTypeId()", "void setData(byte b)", "void setData(byte b, boolean b1)",
                      "boolean setTypeId(int i)", "boolean setTypeId(int i, boolean b)",
                      "boolean setTypeIdAndData(int i, byte b, boolean b1)"):
        source, count = re.subn(r"  @Override\n  public " + re.escape(signature),
                                "  public " + signature, source)
        if count != 1:
            raise ValueError(f"Pinned fallback block target missing: {signature}")
    source = source.replace("import dev.yanianz.intave.world.WorldHeight;\n", "")
    source = replace_method(source, "  public int getY()",
                            "  public int getY() { return getWorld().getMinHeight() - 1; }")
    source = replace_method(source, "  public Location getLocation(Location location)", """  public Location getLocation(Location location) {
    if (location == null) return null;
    location.setWorld(getWorld());
    location.setX(getX()); location.setY(getY()); location.setZ(getZ());
    return location;
  }""")
    source = replace_method(source, "  public PistonMoveReaction getPistonMoveReaction()", """  public PistonMoveReaction getPistonMoveReaction() {
    return PistonMoveReaction.getById(airState().getPistonPushReaction().ordinal());
  }""")
    fragment = (Path(__file__).parent / "templates/intave-native/FakeFallbackBlock.methods").read_text()
    closing = source.rfind("}")
    if closing < 0:
        raise ValueError("Pinned fallback block has no closing brace")
    return source[:closing] + fragment + source[closing:]
