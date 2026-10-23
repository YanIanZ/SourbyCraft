package dev.iyanz.sourbycraft.awf.multiprofile;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

class ProfileStateCodecTest {

    private static ProfileState sample() {
        return new ProfileState(1,
            List.of(new ProfileState.Item(0, new byte[] {1, 2, 3}), new ProfileState.Item(40, new byte[] {9})),
            List.of(new ProfileState.Item(0, new byte[] {4})),
            List.of(new ProfileState.Item(0, new byte[] {5, 6})),
            List.of(new ProfileState.Item(26, new byte[] {7})),
            42, 0.5f, 17.5, 12, 3.25f,
            List.of(new ProfileState.Effect("minecraft:speed", 200, 1, true, false, true)),
            new ProfileState.Location("skyblock", 0.5, 64, 0.5, 90.0f, 0.0f),
            new ProfileState.Location("skyblock", 1.5, 70, 2.5, 45.0f, 10.0f),
            Map.of("example:1", new byte[] {8, 9}));
    }

    @Test
    void everyCategoryRoundTrips() {
        final ProfileState decoded = ProfileStateCodec.decode(ProfileStateCodec.encode(sample()));
        final ProfileState expected = sample();
        assertEquals(expected.schema(), decoded.schema());
        assertEquals(expected.level(), decoded.level());
        assertEquals(expected.experience(), decoded.experience());
        assertEquals(expected.health(), decoded.health());
        assertEquals(expected.food(), decoded.food());
        assertEquals(expected.saturation(), decoded.saturation());
        assertEquals(expected.effects(), decoded.effects());
        assertEquals(expected.spawn(), decoded.spawn());
        assertEquals(expected.returnPoint(), decoded.returnPoint());
        assertEquals(expected.inventory().size(), decoded.inventory().size());
        for (int i = 0; i < expected.inventory().size(); i++) {
            assertEquals(expected.inventory().get(i).slot(), decoded.inventory().get(i).slot());
            assertArrayEquals(expected.inventory().get(i).data(), decoded.inventory().get(i).data());
        }
        assertArrayEquals(new byte[] {8, 9}, decoded.adapters().get("example:1"));
    }

    @Test
    void encodingIsDeterministicAndChecksummed() {
        final byte[] first = ProfileStateCodec.encode(sample());
        final byte[] second = ProfileStateCodec.encode(sample());
        assertArrayEquals(first, second);
        assertEquals(ProfileStateCodec.checksum(first), ProfileStateCodec.checksum(second));
        assertEquals(64, ProfileStateCodec.checksum(first).length());
    }

    @Test
    void aTruncatedPayloadIsRefused() {
        final byte[] encoded = ProfileStateCodec.encode(sample());
        final byte[] truncated = java.util.Arrays.copyOf(encoded, encoded.length - 3);
        final ProfileStateCodec.ProfileStateFormatException failed = assertThrows(
            ProfileStateCodec.ProfileStateFormatException.class, () -> ProfileStateCodec.decode(truncated));
        assertTrue(failed.getMessage().contains("truncated") || failed.getMessage().contains("invalid"));
    }

    @Test
    void trailingBytesAreRefused() {
        final byte[] encoded = ProfileStateCodec.encode(sample());
        final byte[] extended = java.util.Arrays.copyOf(encoded, encoded.length + 1);
        assertThrows(ProfileStateCodec.ProfileStateFormatException.class, () -> ProfileStateCodec.decode(extended));
    }

    @Test
    void aForeignPayloadIsRefusedBeforeAnyAllocation() {
        final byte[] notOurs = "not a profile state".getBytes(StandardCharsets.UTF_8);
        assertThrows(ProfileStateCodec.ProfileStateFormatException.class, () -> ProfileStateCodec.decode(notOurs));
    }

    @Test
    void aHugeDeclaredLengthIsRefused() {
        final byte[] encoded = ProfileStateCodec.encode(ProfileState.empty());
        // Corrupt the first item count (offset: magic 4 + format 1 + schema 4) to Integer.MAX_VALUE.
        final byte[] corrupt = encoded.clone();
        corrupt[9] = 0x7f;
        corrupt[10] = (byte) 0xff;
        corrupt[11] = (byte) 0xff;
        corrupt[12] = (byte) 0xff;
        assertThrows(ProfileStateCodec.ProfileStateFormatException.class, () -> ProfileStateCodec.decode(corrupt));
    }

    @Test
    void emptyStateIsStable() {
        final ProfileState state = ProfileState.empty();
        assertTrue(state.isEmpty());
        final ProfileState decoded = ProfileStateCodec.decode(ProfileStateCodec.encode(state));
        assertEquals(20.0, decoded.health());
        assertEquals(20, decoded.food());
        assertNotEquals(0, ProfileStateCodec.encode(decoded).length);
    }
}
