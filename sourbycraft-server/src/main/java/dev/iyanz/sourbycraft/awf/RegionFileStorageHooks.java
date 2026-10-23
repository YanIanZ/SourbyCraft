package dev.iyanz.sourbycraft.awf;

import ca.spottedleaf.moonrise.patches.chunk_system.io.MoonriseRegionFileIO.RegionDataController.ReadData;
import ca.spottedleaf.moonrise.patches.chunk_system.io.MoonriseRegionFileIO.RegionDataController.WriteData;
import java.io.ByteArrayInputStream;
import java.io.DataInputStream;
import java.io.IOException;
import java.nio.file.Path;
import net.minecraft.SharedConstants;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.NbtAccounter;
import net.minecraft.nbt.NbtIo;
import net.minecraft.nbt.StreamTagVisitor;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.chunk.storage.RegionFileStorage;

/** Ledgered Minecraft/Moonrise adapter for feature patches 0021 and 0053. */
public final class RegionFileStorageHooks {
    private RegionFileStorageHooks() {}

    public static WriteData startWrite(final AwfRegionStorage awf, final int chunkX, final int chunkZ, final CompoundTag compound) throws IOException {
        // SourbyCraft start - Aurora World Fabric: serialize here, hand the bytes to AWF in finishWrite
        if (awf != null) {
            final byte[] nbt = compound == null ? null : dev.iyanz.sourbycraft.awf.AwfChunkNbt.encode(compound);
            return new WriteData(
                compound, compound == null ? WriteData.WriteResult.DELETE : WriteData.WriteResult.WRITE,
                null, ignored -> awf.write(chunkX, chunkZ, nbt));
        }
        // SourbyCraft end - Aurora World Fabric
        return null;
    }

    public static boolean finishWrite(final AwfRegionStorage awf, final WriteData writeData) throws IOException {
        dev.iyanz.sourbycraft.perf.RegionIoCounters.GLOBAL.write(writeData.result() == WriteData.WriteResult.DELETE); // SourbyCraft - /perf storage
        // SourbyCraft start - Aurora World Fabric: the write goes to AWF, never to a region file
        if (awf != null) {
            writeData.write().run(null);
            return true;
        }
        // SourbyCraft end - Aurora World Fabric
        return false;
    }

    public static ReadData readData(final AwfRegionStorage awf, final int chunkX, final int chunkZ) throws IOException {
        dev.iyanz.sourbycraft.perf.RegionIoCounters.GLOBAL.read(); // SourbyCraft - /perf storage
        // SourbyCraft start - Aurora World Fabric: a chunk AWF owns never reaches the region file
        if (awf != null) {
            final byte[] owned = awf.read(chunkX, chunkZ);
            if (owned == dev.iyanz.sourbycraft.awf.AwfRegionStorage.DELETED) {
                return new ReadData(
                    ReadData.ReadResult.NO_DATA, null, null, 0);
            }
            if (owned != null) {
                return new ReadData(
                    ReadData.ReadResult.SYNC_READ, null, dev.iyanz.sourbycraft.awf.AwfChunkNbt.decode(owned), 0);
            }
            // Never touched since AWF was attached: the region file is still authoritative.
        }
        // SourbyCraft end - Aurora World Fabric
        return null;
    }

    public record ReadResult(CompoundTag value) {}

    public static ReadResult read(final AwfRegionStorage awf, final ChunkPos pos) throws IOException {
        // SourbyCraft start - Aurora World Fabric
        if (awf != null) {
            final byte[] owned = awf.read(pos.x(), pos.z());
            if (owned == dev.iyanz.sourbycraft.awf.AwfRegionStorage.DELETED) return new ReadResult(null);
            if (owned != null) return new ReadResult(dev.iyanz.sourbycraft.awf.AwfChunkNbt.decode(owned));
        }
        // SourbyCraft end - Aurora World Fabric
        return null;
    }

    public static boolean scanChunk(final AwfRegionStorage awf, final ChunkPos pos, final StreamTagVisitor scanner) throws IOException {
        // Feature 0053: one attempt, no fence, refused while the store is failing.
        if (awf != null) {
            final byte[] owned = awf.scanRead(pos.x(), pos.z());
            if (owned == AwfRegionStorage.DELETED) return true;
            if (owned != null) {
                try (DataInputStream in = new DataInputStream(new ByteArrayInputStream(owned))) {
                    NbtIo.parse(in, scanner, NbtAccounter.unlimitedHeap());
                }
                return true;
            }
        }
        return false;
    }

    public static boolean write(final AwfRegionStorage awf, final ChunkPos pos, final CompoundTag value) throws IOException {
        // SourbyCraft start - Aurora World Fabric: region files under an AWF store are never written
        if (awf != null) {
            if (!SharedConstants.DEBUG_DONT_SAVE_WORLD) {
                awf.write(pos.x(), pos.z(), value == null ? null : dev.iyanz.sourbycraft.awf.AwfChunkNbt.encode(value));
            }
            return true;
        }
        // SourbyCraft end - Aurora World Fabric
        return false;
    }

    public static void close(final AwfRegionStorage awf, final Path folder, final org.slf4j.Logger logger) throws IOException {
        // SourbyCraft start - Aurora World Fabric: commit what is in memory before anything closes
        if (awf != null) {
            try {
                awf.close();
            } catch (final IOException | RuntimeException failed) {
                logger.error("Aurora World Fabric could not commit {} on close; its uncommitted chunks are lost", folder, failed);
                throw failed;
            }
        }
        // SourbyCraft end - Aurora World Fabric
    }

    public static void flush(final AwfRegionStorage awf) throws IOException {
        if (awf != null) awf.flush();
    }

    public static AwfRegionStorage attach(final RegionFileStorage storage, final Path folder) {
        try {
            return dev.iyanz.sourbycraft.awf.AwfEngine.attach(folder, new dev.iyanz.sourbycraft.awf.AwfEngine.RegionSink() {
                @Override
                public void write(final int chunkX, final int chunkZ, final byte[] nbt) throws IOException {
                    storage.write(new ChunkPos(chunkX, chunkZ), nbt == null ? null : dev.iyanz.sourbycraft.awf.AwfChunkNbt.decode(nbt));
                }

                @Override
                public void flush() throws IOException {
                    storage.flush();
                }
            });
        } catch (final IOException failed) {
            // Falling back to the region files would load chunks older than the ones AWF holds.
            throw new java.io.UncheckedIOException("Aurora World Fabric could not open the store for " + folder
                + "; refusing to fall back to the region files it shadows", failed);
        }
    }
}
