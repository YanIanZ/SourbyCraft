package dev.iyanz.sourbycraft.compat.luminol;

import ca.spottedleaf.concurrentutil.map.concurrent.longs.ConcurrentChainedLong2ReferenceHashTable;
import ca.spottedleaf.moonrise.common.util.CoordinateUtils;
import ca.spottedleaf.moonrise.common.util.TickThread;
import ca.spottedleaf.moonrise.patches.chunk_system.scheduling.ChunkHolderManager;
import ca.spottedleaf.moonrise.patches.chunk_system.ticket.ChunkSystemTicketType;
import io.papermc.paper.threadedregions.RegionizedWorldData;
import io.papermc.paper.threadedregions.ThreadedRegionizer;
import io.papermc.paper.threadedregions.TickRegionScheduler;
import io.papermc.paper.threadedregions.TickRegions;
import java.util.concurrent.atomic.AtomicLong;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.TicketType;

/**
 * Reference-counted access to the {@link RegionizedWorldData} of the region that owns a chunk, from any thread.
 *
 * <p>Ported from LightingLuminol (GPL-3.0; Bacteriawa, 2026-06-06; revision 72d51c4d,
 * {@code lightingluminol-server/src/main/java/meow/bacteriawa/lightingluminol/core/ReferenceCountedRegionizedWorldDataPool.java},
 * itself modelled on Folia's {@code RegionizedTaskQueue}). On the thread that owns the chunk this is the current
 * region's data with no ticket. Off it, a ticket (own type, {@link #POOL_TICKET}; LightingLuminol reused
 * {@code RegionizedTaskQueue.TASK_QUEUE_TICKET}) keeps the owning region alive while the caller holds the lease,
 * and the region's data is returned so the caller's write lands in the structure that region ticks.</p>
 *
 * <p>Only used while {@code aurora.bridge.thread-checks} is relaxed ({@code guards/ThreadChecks.RELAXED_SCOPE});
 * see {@link WorldDataAccess}. Honesty, as in the LightingLuminol gap analysis: the ticket prevents an unload, not a
 * merge or split -- a write into a region's data that is merged into another region before that region ticks is
 * lost (the entity or chunk silently stops being ticked by anyone); and the region's lists are then mutated by
 * two threads, which the {@code synchronized} wrappers the same patch adds make individually safe but not
 * atomic across lists. This class exists so a relaxed server does not fail immediately; it does not make the
 * access correct.</p>
 *
 * <p>Deviation from the port: {@link #acquire} never returns {@code null}. When no region covers the chunk (not
 * loaded) the current region's data is returned when it is this world's, otherwise an
 * {@link IllegalStateException} names the chunk; LightingLuminol returned {@code null} and failed at the use site.</p>
 */
public final class RegionizedWorldDataPool {

    /** Keeps the owning region alive while a lease is held. */
    public static final TicketType<Long> POOL_TICKET = ChunkSystemTicketType.create("sourbycraft_world_data_pool", Long::compareTo);

    private final ConcurrentChainedLong2ReferenceHashTable<ReferenceCountData> referenceCounters = new ConcurrentChainedLong2ReferenceHashTable<>();
    private final ServerLevel world;
    private volatile int mask = -1;

    public RegionizedWorldDataPool(final ServerLevel world) {
        this.world = world;
    }

    /** The world data to write and the reference to release; {@code ticket == null} when the caller owns the chunk. */
    public record Lease(RegionizedWorldData data, ReferenceCountData ticket, long sectionCoord) {}

    private long computeCoord(final long chunkKey) {
        if (this.mask == -1) {
            this.mask = (1 << this.world.regioniser.sectionChunkShift) - 1;
        }
        final int chunkX = CoordinateUtils.getChunkX(chunkKey) & ~this.mask;
        final int chunkZ = CoordinateUtils.getChunkZ(chunkKey) & ~this.mask;
        return CoordinateUtils.getChunkKey(chunkX, chunkZ);
    }

    /** The owning region's data for the chunk, holding a ticket off the owning thread. Release with {@link #release}. */
    public Lease acquire(final int chunkX, final int chunkZ) {
        final RegionizedWorldData current = TickRegionScheduler.getCurrentRegionizedWorldData();
        if (current != null && current.world == this.world && TickThread.isTickThreadFor(this.world, chunkX, chunkZ)) {
            // the owning thread: the fast path, nothing to hold
            return new Lease(current, null, 0L);
        }

        final long coord = CoordinateUtils.getChunkKey(chunkX, chunkZ);
        final long sectionCoord = this.computeCoord(coord);
        final ReferenceCountData ticket = this.incrementReference(sectionCoord);

        ThreadedRegionizer.ThreadedRegion<TickRegions.TickRegionData, TickRegions.TickRegionSectionData> region =
            this.world.regioniser.getRegionAtUnsynchronised(chunkX, chunkZ);
        if (region == null) {
            region = this.world.regioniser.getRegionAtSynchronised(chunkX, chunkZ);
        }

        RegionizedWorldData data = region == null ? null : region.getData().getRegionizedData(this.world.worldRegionData);
        if (data == null) {
            if (current != null && current.world == this.world) {
                data = current;
            } else {
                this.decrementReference(ticket, sectionCoord);
                throw new IllegalStateException("No region owns chunk (" + chunkX + "," + chunkZ + ") of world '"
                    + this.world.getWorld().getName() + "'; relaxed thread checks cannot route this access from thread "
                    + Thread.currentThread().getName());
            }
        }
        return new Lease(data, ticket, sectionCoord);
    }

    public void release(final Lease lease) {
        if (lease.ticket() != null) {
            this.decrementReference(lease.ticket(), lease.sectionCoord());
        }
    }

    // Ticket maintenance below is LightingLuminol's copy of RegionizedTaskQueue's reference counting.

    private void removeTicket(final long coord, final long id) {
        this.world.moonrise$getChunkTaskScheduler().chunkHolderManager.removeTicketAtLevel(
            POOL_TICKET, coord, ChunkHolderManager.MAX_TICKET_LEVEL, id
        );
    }

    private void addTicket(final long coord, final long id) {
        this.world.moonrise$getChunkTaskScheduler().chunkHolderManager.addTicketAtLevel(
            POOL_TICKET, coord, ChunkHolderManager.MAX_TICKET_LEVEL, id
        );
    }

    private void processTicketUpdates(final long coord) {
        this.world.moonrise$getChunkTaskScheduler().chunkHolderManager.processTicketUpdates(CoordinateUtils.getChunkX(coord), CoordinateUtils.getChunkZ(coord));
    }

    // note: only call on acquired referenceCountData
    private void ensureTicketAdded(final long coord, final ReferenceCountData referenceCountData) {
        if (!referenceCountData.addedTicket) {
            // fine if multiple threads do this, no removeTicket may be called for this coord due to reference count inc
            this.addTicket(coord, referenceCountData.id);
            this.processTicketUpdates(coord);
            referenceCountData.addedTicket = true;
        }
    }

    private void decrementReference(final ReferenceCountData referenceCountData, final long coord) {
        if (!referenceCountData.decreaseReferenceCount()) {
            return;
        } // else: need to remove ticket

        final ReferenceCountData[] toRemoveTicket = new ReferenceCountData[1];

        // note: it is possible that another thread increments and then removes the reference before we can, so
        //       use ifPresent
        this.referenceCounters.computeIfPresent(coord, (final long keyInMap, final ReferenceCountData valueInMap) -> {
            if (valueInMap.referenceCount.get() != 0L) {
                return valueInMap;
            }

            // note: valueInMap may not be referenceCountData
            toRemoveTicket[0] = valueInMap;

            return null;
        });

        if (toRemoveTicket[0] != null) {
            this.removeTicket(coord, toRemoveTicket[0].id);
        }
    }

    private ReferenceCountData incrementReference(final long coord) {
        ReferenceCountData referenceCountData = this.referenceCounters.get(coord);

        if (referenceCountData != null && referenceCountData.addCount()) {
            this.ensureTicketAdded(coord, referenceCountData);
            return referenceCountData;
        }

        referenceCountData = this.referenceCounters.compute(coord, (final long keyInMap, final ReferenceCountData valueInMap) -> {
            if (valueInMap == null) {
                // sets reference count to 1
                return new ReferenceCountData();
            }
            // OK if we add from 0, the remove call will use compute() and catch this race condition
            valueInMap.referenceCount.getAndIncrement();

            return valueInMap;
        });

        this.ensureTicketAdded(coord, referenceCountData);

        return referenceCountData;
    }

    public static final class ReferenceCountData {
        private static final AtomicLong ID_GENERATOR = new AtomicLong();

        private final long id = ID_GENERATOR.getAndIncrement();

        public final AtomicLong referenceCount = new AtomicLong(1L);
        public volatile boolean addedTicket;

        // returns false if reference count is 0, otherwise increments ref count
        public boolean addCount() {
            int failures = 0;
            for (long curr = this.referenceCount.get();;) {
                for (int i = 0; i < failures; ++i) {
                    Thread.onSpinWait();
                }

                if (curr == 0L) {
                    return false;
                }

                if (curr == (curr = this.referenceCount.compareAndExchange(curr, curr + 1L))) {
                    return true;
                }

                ++failures;
            }
        }

        // returns true if new reference count is 0
        public boolean decreaseReferenceCount() {
            final long res = this.referenceCount.decrementAndGet();
            if (res >= 0L) {
                return res == 0L;
            } else {
                throw new IllegalStateException("Negative reference count");
            }
        }
    }
}
