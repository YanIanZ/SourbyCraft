package dev.iyanz.sourbycraft.awf;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.NbtIo;

/**
 * How {@code RegionFileStorage} turns a chunk tag into the bytes Aurora World Fabric stores and back
 * (feature patch 0021 calls these; they were private methods of that upstream class).
 *
 * <p>The bytes are vanilla's uncompressed named-root NBT, {@code NbtIo.write}; decoding is
 * {@code NbtIo.read}, with its unlimited heap accounting, as before the move. Changing either
 * changes the on-disk format of every AWF store, so {@code AwfChunkNbtTest} pins both.</p>
 */
public final class AwfChunkNbt {

    private AwfChunkNbt() {}

    public static byte[] encode(final CompoundTag tag) throws IOException {
        final ByteArrayOutputStream bytes = new ByteArrayOutputStream(16 * 1024);
        try (DataOutputStream out = new DataOutputStream(bytes)) {
            NbtIo.write(tag, out);
        }
        return bytes.toByteArray();
    }

    public static CompoundTag decode(final byte[] nbt) throws IOException {
        try (DataInputStream in = new DataInputStream(new ByteArrayInputStream(nbt))) {
            return NbtIo.read(in);
        }
    }
}
