package dev.iyanz.sourbycraft.awf;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.io.ByteArrayOutputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.NbtIo;
import net.minecraft.nbt.StringTag;
import org.junit.jupiter.api.Test;

/**
 * The chunk NBT codec between {@code RegionFileStorage} and Aurora World Fabric (feature patch 0021
 * calls it; it used to be two private methods inside that upstream class). Pins that the bytes are
 * exactly vanilla's uncompressed {@code NbtIo.write} form and that decoding is {@code NbtIo.read}, so
 * every chunk already stored in an AWF store keeps decoding to the same tag.
 */
class AwfChunkNbtTest {

    private static CompoundTag chunk() {
        final CompoundTag tag = new CompoundTag();
        tag.putInt("xPos", -3);
        tag.putInt("zPos", 7);
        tag.putLong("LastUpdate", 123_456_789L);
        final ListTag sections = new ListTag();
        sections.add(StringTag.valueOf("section"));
        tag.put("sections", sections);
        return tag;
    }

    @Test
    void encodeIsVanillaUncompressedNbt() throws IOException {
        final ByteArrayOutputStream expected = new ByteArrayOutputStream();
        try (DataOutputStream out = new DataOutputStream(expected)) {
            NbtIo.write(chunk(), out);
        }
        assertArrayEquals(expected.toByteArray(), AwfChunkNbt.encode(chunk()));
    }

    @Test
    void decodeReadsWhatEncodeWrote() throws IOException {
        assertEquals(chunk(), AwfChunkNbt.decode(AwfChunkNbt.encode(chunk())));
        assertEquals(new CompoundTag(), AwfChunkNbt.decode(AwfChunkNbt.encode(new CompoundTag())));
    }

    @Test
    void truncatedBytesFailTheWayNbtIoReadFails() throws IOException {
        final byte[] bytes = AwfChunkNbt.encode(chunk());
        final byte[] truncated = java.util.Arrays.copyOf(bytes, bytes.length / 2);
        final Exception vanilla = assertThrows(Exception.class,
            () -> NbtIo.read(new java.io.DataInputStream(new java.io.ByteArrayInputStream(truncated))));
        final Exception ours = assertThrows(Exception.class, () -> AwfChunkNbt.decode(truncated));
        assertEquals(vanilla.getClass(), ours.getClass(), "same failure type as NbtIo.read (ReportedNbtException in 26.2)");
    }
}
