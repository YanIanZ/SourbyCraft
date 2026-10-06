package dev.yanianz.intave.block.variant.index;

import dev.yanianz.intave.integration.NativeBlockStates;
import java.util.Map;
import org.bukkit.Material;

final class ModernIndexer implements Indexer {
    @Override public Map<Object, Integer> index(Material type) { return NativeBlockStates.index(type); }
}
