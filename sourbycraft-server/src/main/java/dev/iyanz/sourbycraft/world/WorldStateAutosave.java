package dev.iyanz.sourbycraft.world;

import com.mojang.logging.LogUtils;
import dev.iyanz.sourbycraft.execution.region.RegionOwnership;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.function.Supplier;
import net.minecraft.world.level.TicketStorage;
import net.minecraft.world.level.saveddata.SavedData;
import net.minecraft.world.level.saveddata.SavedDataType;
import net.minecraft.world.level.storage.SavedDataStorage;
import org.slf4j.Logger;

/**
 * Patch 0067: encode detached ticket data on the global tick, then use the storage's existing I/O lane.
 * Raids and world PDC deliberately remain shutdown-only: neither exposes a consistent concurrent copy.
 * No region-owned raid state or plugin PDC is read here.
 */
public final class WorldStateAutosave {
    private static final Logger LOGGER = LogUtils.getLogger();

    private WorldStateAutosave() {}

    public static void save(final SavedDataStorage storage, final boolean sync) {
        RegionOwnership.current().ensureGlobalTickThread("world state autosave");
        final SavedData live = storage.cache.getOrDefault(TicketStorage.TYPE, Optional.empty()).orElse(null);
        if (live instanceof TicketStorage tickets) {
            saveItems(storage, List.of(new Item<>(TicketStorage.TYPE, tickets, tickets::sourbycraft$snapshot)), sync);
        }
    }

    record Item<T extends SavedData>(SavedDataType<T> type, T live, Supplier<T> snapshot) {}

    /**
     * Clear before copying, never after: a writer publishing dirty during/after capture must retain it.
     * Failed capture/encode re-marks only its item; each item is independently scheduled before any join.
     * File I/O errors follow SavedDataStorage's existing logging (not durable-write acknowledgement).
     */
    static CompletableFuture<?> saveItems(final SavedDataStorage storage, final List<? extends Item<?>> items,
                                          final boolean sync) {
        final List<CompletableFuture<?>> writes = new ArrayList<>();
        for (final Item<?> item : items) {
            if (!item.live().isDirty()) {
                continue;
            }
            item.live().setDirty(false);
            try {
                writes.add(saveSnapshot(storage, item).whenComplete((ignored, failure) -> {
                    if (failure != null) {
                        item.live().setDirty();
                        LOGGER.warn("Could not complete periodic save of {}", item.type().id(), failure);
                    }
                }));
            } catch (final RuntimeException failure) {
                item.live().setDirty();
                LOGGER.warn("Could not snapshot/encode {}; kept dirty for the next autosave", item.type().id(), failure);
            }
        }
        final CompletableFuture<?> all = CompletableFuture.allOf(writes.toArray(CompletableFuture[]::new));
        if (sync) {
            all.join();
        }
        return all;
    }

    private static <T extends SavedData> CompletableFuture<?> saveSnapshot(final SavedDataStorage storage, final Item<T> item) {
        return storage.sourbycraft$saveSnapshot(item.type(), item.snapshot().get());
    }
}
