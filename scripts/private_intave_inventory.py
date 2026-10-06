"""Reproducible 26.2 API migration for Intave's detached simulation inventory."""
from pathlib import Path
import re


def modernize_inventory(source: str, replace_method) -> str:
    # Keep upstream copyright and compatibility methods; only their obsolete annotations go.
    legacy = ("String getName()", "String getTitle()", "boolean contains(int i)",
              "boolean contains(int i, int i1)", "HashMap<Integer, ? extends ItemStack> all(int i)",
              "int first(int i)", "void remove(int i)", "int clear(int i, int i1)")
    for signature in legacy:
        pattern = r"  @Override\n  public " + re.escape(signature)
        source, count = re.subn(pattern, "  public " + signature, source)
        if count != 1:
            raise ValueError(f"Pinned inventory compatibility target missing: {signature}")
    source = source.replace("private final ItemStack[] extra = new ItemStack[4];",
                            "private final ItemStack[] extra = new ItemStack[3];")
    methods = {
        "int getSize()": "return storage.length + armor.length + extra.length;",
        "ItemStack getItem(int i)": """checkSlot(i);
    return i < 36 ? storage[i] : i < 40 ? armor[i - 36] : extra[i - 40];""",
        "void setItem(int i, ItemStack itemStack)": """checkSlot(i);
    if (i < 36) storage[i] = itemStack;
    else if (i < 40) armor[i - 36] = itemStack;
    else extra[i - 40] = itemStack;""",
        "void setContents(ItemStack[] itemStacks)": """java.util.Objects.requireNonNull(itemStacks, "contents");
    checkContents(itemStacks, getSize());
    clear();
    for (int i = 0; i < itemStacks.length; i++) setItem(i, itemStacks[i]);""",
        "void setStorageContents(ItemStack[] itemStacks)": """java.util.Objects.requireNonNull(itemStacks, "contents");
    checkContents(itemStacks, storage.length);
    java.util.Arrays.fill(storage, null);
    System.arraycopy(itemStacks, 0, storage, 0, itemStacks.length);""",
        "void setArmorContents(ItemStack[] itemStacks)": """checkContents(itemStacks, armor.length);
    java.util.Arrays.fill(armor, null);
    if (itemStacks != null) System.arraycopy(itemStacks, 0, armor, 0, itemStacks.length);""",
        "void setExtraContents(ItemStack[] itemStacks)": """checkContents(itemStacks, extra.length);
    java.util.Arrays.fill(extra, null);
    if (itemStacks != null) System.arraycopy(itemStacks, 0, extra, 0, itemStacks.length);""",
        "HashMap<Integer, ItemStack> removeItem(ItemStack... itemStacks)": "return removeAcross(storage.length, itemStacks);",
        "int firstEmpty()": """for (int i = 0; i < storage.length; i++) if (empty(storage[i])) return i;
    return -1;""",
        "void setHeldItemSlot(int i)": """if (i < 0 || i >= 9) throw new IllegalArgumentException("Held slot must be in 0..8");
    heldItemSlot = i;""",
        "ListIterator<ItemStack> iterator()": "return iterator(0);",
        "ListIterator<ItemStack> iterator(int i)": """return new java.util.AbstractList<ItemStack>() {
      @Override public int size() { return getSize(); }
      @Override public ItemStack get(int index) { return getItem(index); }
      @Override public ItemStack set(int index, ItemStack stack) {
        ItemStack previous = getItem(index);
        setItem(index, stack);
        return previous;
      }
    }.listIterator(i);""",
    }
    for signature, body in methods.items():
        marker = "  public " + signature
        source = replace_method(source, marker, marker + " {\n    " + body + "\n  }")
    fragment = (Path(__file__).parent / "templates/intave-native/MockEmptyInventory.methods").read_text()
    closing = source.rfind("}")
    if closing < 0:
        raise ValueError("Pinned inventory class has no closing brace")
    return source[:closing] + fragment + source[closing:]
