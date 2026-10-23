package dev.iyanz.sourbycraft.abysslink;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.fail;

import io.netty.buffer.ByteBuf;
import io.netty.buffer.Unpooled;
import io.netty.channel.embedded.EmbeddedChannel;
import io.netty.handler.codec.CorruptedFrameException;
import io.netty.handler.codec.TooLongFrameException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Test;

/**
 * Codec and TLV acceptance tests for AbyssLink v1.
 *
 * <p>The golden frame is the Leviathan v1 heartbeat vector: {@code ABYL}, major 1, minor 0, type 3,
 * flags 0, zero-length payload, request ID 1. Its twenty bytes are asserted exactly, so a change to
 * any header field on either direction fails here.</p>
 *
 * <p>The minor byte is data, not a codec filter: any {@code 0..255} round-trips verbatim, and only
 * {@link AbyssLinkSession} rejects a minor the handshake did not negotiate.</p>
 */
class AbyssLinkFrameCodecTest {

    private static final int MAX_PAYLOAD = AbyssLinkFrame.MAX_PAYLOAD;
    private static final HexFormat HEX = HexFormat.of();
    private static final byte[] HEARTBEAT_GOLDEN = HEX.parseHex("4142594c01000300000000000000000000000001");

    private static final int MAJOR_OFFSET = 4;
    private static final int MINOR_OFFSET = 5;
    private static final int TYPE_OFFSET = 6;
    private static final int FLAGS_OFFSET = 7;
    private static final int LENGTH_OFFSET = 8;

    // --- frame encoding ---------------------------------------------------------------------

    @Test
    void encodesLeviathanHeartbeatGoldenVector() {
        assertArrayEquals(HEARTBEAT_GOLDEN, encode(new AbyssLinkFrame(3, 1, new byte[0])));
    }

    @Test
    void decodesLeviathanHeartbeatGoldenVector() {
        final EmbeddedChannel channel = new EmbeddedChannel(new AbyssLinkFrameCodec(MAX_PAYLOAD));
        try {
            assertTrue(channel.writeInbound(Unpooled.wrappedBuffer(HEARTBEAT_GOLDEN.clone())));
            final AbyssLinkFrame frame = channel.readInbound();
            assertNotNull(frame);
            assertEquals(0, frame.minor());
            assertEquals(3, frame.type());
            assertEquals(1L, frame.requestId());
            assertArrayEquals(new byte[0], frame.payload());
            assertNull(channel.readInbound());
        } finally {
            releaseQuietly(channel);
        }
    }

    @Test
    void acceptsEveryTypeTheContractAllows() {
        for (final int type : new int[]{1, 2, 3, 4, 5, 6, 7, 255}) {
            final EmbeddedChannel channel = new EmbeddedChannel(new AbyssLinkFrameCodec(MAX_PAYLOAD));
            try {
                final byte[] wire = goldenWith(TYPE_OFFSET, (byte) type);
                assertTrue(channel.writeInbound(Unpooled.wrappedBuffer(wire)), "type " + type);
                final AbyssLinkFrame frame = channel.readInbound();
                assertEquals(type, frame.type());
                assertEquals(type, encode(new AbyssLinkFrame(type, 1, new byte[0]))[TYPE_OFFSET] & 255);
            } finally {
                releaseQuietly(channel);
            }
        }
    }

    @Test
    void preservesRaw64RequestIdBits() {
        for (final long requestId : new long[]{0L, 1L, -1L, Long.MIN_VALUE, Long.MAX_VALUE, 0x0123456789abcdefL}) {
            final EmbeddedChannel channel = new EmbeddedChannel(new AbyssLinkFrameCodec(MAX_PAYLOAD));
            try {
                assertTrue(channel.writeInbound(Unpooled.wrappedBuffer(encode(new AbyssLinkFrame(3, requestId, new byte[0])))));
                assertEquals(requestId, channel.<AbyssLinkFrame>readInbound().requestId());
            } finally {
                releaseQuietly(channel);
            }
        }
    }

    // --- framing behaviour ------------------------------------------------------------------

    @Test
    void decodesEveryHeaderSplitOnceTheHeaderIsComplete() {
        for (int split = 1; split < AbyssLinkFrameCodec.HEADER_SIZE; split++) {
            final EmbeddedChannel channel = new EmbeddedChannel(new AbyssLinkFrameCodec(MAX_PAYLOAD));
            try {
                assertFalse(channel.writeInbound(Unpooled.wrappedBuffer(Arrays.copyOfRange(HEARTBEAT_GOLDEN, 0, split))),
                    "partial header at " + split + " must not emit");
                assertNull(channel.readInbound());
                assertTrue(channel.writeInbound(
                    Unpooled.wrappedBuffer(Arrays.copyOfRange(HEARTBEAT_GOLDEN, split, HEARTBEAT_GOLDEN.length))));
                final AbyssLinkFrame frame = channel.readInbound();
                assertEquals(3, frame.type());
                assertEquals(1L, frame.requestId());
            } finally {
                releaseQuietly(channel);
            }
        }
    }

    @Test
    void decodesBytewiseFragmentedFrameOnlyWhenComplete() {
        final byte[] payload = AbyssLinkTlv.encode(Map.of(1,
            AbyssLinkTlv.value(UUID.fromString("00000000-0000-0000-0000-000000000001"))));
        final byte[] wire = encode(new AbyssLinkFrame(4, -1L, payload));
        final EmbeddedChannel channel = new EmbeddedChannel(new AbyssLinkFrameCodec(MAX_PAYLOAD));
        try {
            for (int i = 0; i < wire.length; i++) {
                final boolean emitted = channel.writeInbound(Unpooled.wrappedBuffer(new byte[]{wire[i]}));
                assertEquals(i == wire.length - 1, emitted, "emission at byte " + i);
            }
            final AbyssLinkFrame frame = channel.readInbound();
            assertEquals(-1L, frame.requestId());
            assertArrayEquals(payload, frame.payload());
            assertNull(channel.readInbound());
        } finally {
            releaseQuietly(channel);
        }
    }

    @Test
    void retainsAnIncompletePayloadWithoutEmitting() {
        final byte[] wire = encode(new AbyssLinkFrame(3, 7L, new byte[]{1, 2, 3, 4, 5}));
        final EmbeddedChannel channel = new EmbeddedChannel(new AbyssLinkFrameCodec(MAX_PAYLOAD));
        try {
            assertFalse(channel.writeInbound(Unpooled.wrappedBuffer(Arrays.copyOf(wire, wire.length - 2))));
            assertNull(channel.readInbound());
            assertTrue(channel.writeInbound(
                Unpooled.wrappedBuffer(Arrays.copyOfRange(wire, wire.length - 2, wire.length))));
            assertArrayEquals(new byte[]{1, 2, 3, 4, 5}, channel.<AbyssLinkFrame>readInbound().payload());
        } finally {
            releaseQuietly(channel);
        }
    }

    @Test
    void decodesCoalescedFramesInOneRead() {
        final EmbeddedChannel channel = new EmbeddedChannel(new AbyssLinkFrameCodec(MAX_PAYLOAD));
        try {
            assertTrue(channel.writeInbound(Unpooled.wrappedBuffer(concat(HEARTBEAT_GOLDEN, HEARTBEAT_GOLDEN, HEARTBEAT_GOLDEN))));
            for (int i = 0; i < 3; i++) {
                final AbyssLinkFrame frame = channel.readInbound();
                assertNotNull(frame, "frame " + i);
                assertEquals(3, frame.type());
                assertEquals(1L, frame.requestId());
            }
            assertNull(channel.readInbound());
        } finally {
            releaseQuietly(channel);
        }
    }

    @Test
    void emitsCompletedFrameAndRetainsIncompleteTail() {
        final byte[] second = encode(new AbyssLinkFrame(5, 2L, new byte[]{9, 9}));
        final EmbeddedChannel channel = new EmbeddedChannel(new AbyssLinkFrameCodec(MAX_PAYLOAD));
        try {
            assertTrue(channel.writeInbound(Unpooled.wrappedBuffer(concat(HEARTBEAT_GOLDEN, Arrays.copyOf(second, 10)))));
            assertEquals(3, channel.<AbyssLinkFrame>readInbound().type());
            assertNull(channel.readInbound());
            assertTrue(channel.writeInbound(Unpooled.wrappedBuffer(Arrays.copyOfRange(second, 10, second.length))));
            assertEquals(2L, channel.<AbyssLinkFrame>readInbound().requestId());
            assertNull(channel.readInbound());
        } finally {
            releaseQuietly(channel);
        }
    }

    // --- malformed headers ------------------------------------------------------------------

    @Test
    void rejectsInvalidMagic() {
        assertInboundRejected(CorruptedFrameException.class, goldenWith(0, new byte[]{0, 0, 0, 0}), MAX_PAYLOAD);
        assertInboundRejected(CorruptedFrameException.class, goldenWith(0, new byte[]{'A', 'B', 'Y', 'K'}), MAX_PAYLOAD);
        final byte[] reversed = HEARTBEAT_GOLDEN.clone();
        reverse(reversed, 0, 4);
        assertInboundRejected(CorruptedFrameException.class, reversed, MAX_PAYLOAD);
    }

    @Test
    void rejectsUnsupportedMajorVersion() {
        for (final int major : new int[]{0, 2, 255}) {
            assertInboundRejected(CorruptedFrameException.class, goldenWith(MAJOR_OFFSET, (byte) major), MAX_PAYLOAD);
        }
    }

    @Test
    void decodesRawMinorByteForEveryAllowedValue() {
        // The codec must not filter the minor byte: negotiation is the session's job.
        for (final int minor : new int[]{1, 2, 255}) {
            final EmbeddedChannel channel = new EmbeddedChannel(new AbyssLinkFrameCodec(MAX_PAYLOAD));
            try {
                assertTrue(channel.writeInbound(Unpooled.wrappedBuffer(goldenWith(MINOR_OFFSET, (byte) minor))),
                    "minor " + minor);
                assertEquals(minor, channel.<AbyssLinkFrame>readInbound().minor());
            } finally {
                releaseQuietly(channel);
            }
        }
    }

    @Test
    void rejectsUnknownMessageTypes() {
        for (final int type : new int[]{0, 8, 9, 254}) {
            assertInboundRejected(CorruptedFrameException.class, goldenWith(TYPE_OFFSET, (byte) type), MAX_PAYLOAD);
        }
    }

    @Test
    void rejectsAnyNonZeroFlags() {
        for (int bit = 0; bit < 8; bit++) {
            assertInboundRejected(CorruptedFrameException.class, goldenWith(FLAGS_OFFSET, (byte) (1 << bit)), MAX_PAYLOAD);
        }
        assertInboundRejected(CorruptedFrameException.class, goldenWith(FLAGS_OFFSET, (byte) 0xff), MAX_PAYLOAD);
    }

    @Test
    void rejectsUnsignedOversizeHeaderWithNoBodyArriving() {
        // Header only: rejection must come from the declared length, not from buffering a body.
        assertInboundRejected(TooLongFrameException.class, goldenWith(LENGTH_OFFSET, HEX.parseHex("ff000000")), MAX_PAYLOAD);
        assertInboundRejected(TooLongFrameException.class, goldenWith(LENGTH_OFFSET, HEX.parseHex("ffffffff")), MAX_PAYLOAD);
        assertInboundRejected(TooLongFrameException.class, goldenWith(LENGTH_OFFSET, HEX.parseHex("00010001")), MAX_PAYLOAD);
    }

    @Test
    void rejectsPayloadAboveConfiguredCapBeforeBodyArrives() {
        final byte[] header = goldenWith(LENGTH_OFFSET, HEX.parseHex("00000021"));
        assertEquals(AbyssLinkFrameCodec.HEADER_SIZE, header.length);
        assertInboundRejected(TooLongFrameException.class, header, 32);
    }

    // --- bounds and ownership ---------------------------------------------------------------

    @Test
    void acceptsMaximumPayloadInBothDirections() {
        final byte[] payload = new byte[MAX_PAYLOAD];
        Arrays.fill(payload, (byte) 0x5a);
        final byte[] wire = encode(new AbyssLinkFrame(6, 42L, payload));
        assertEquals(MAX_PAYLOAD + AbyssLinkFrameCodec.HEADER_SIZE, wire.length);
        final EmbeddedChannel channel = new EmbeddedChannel(new AbyssLinkFrameCodec(MAX_PAYLOAD));
        try {
            assertTrue(channel.writeInbound(Unpooled.wrappedBuffer(wire)));
            assertArrayEquals(payload, channel.<AbyssLinkFrame>readInbound().payload());
        } finally {
            releaseQuietly(channel);
        }
    }

    @Test
    void refusesOutboundPayloadAboveConfiguredCap() {
        assertOutboundRejected(TooLongFrameException.class, new AbyssLinkFrame(3, 0L, new byte[]{1, 2, 3, 4, 5}), 4);
        assertOutboundRejected(TooLongFrameException.class, new AbyssLinkFrame(3, 0L, new byte[]{1}), 0);
        assertEquals(AbyssLinkFrameCodec.HEADER_SIZE, encode(new AbyssLinkFrame(3, 0L, new byte[0]), 0).length);
        final byte[] inbound = goldenWith(LENGTH_OFFSET, HEX.parseHex("00000001"));
        assertInboundRejected(TooLongFrameException.class, inbound, 0);
    }

    @Test
    void refusesInvalidFramesAndLimits() {
        assertThrows(IllegalArgumentException.class, () -> new AbyssLinkFrame(3, 0L, new byte[MAX_PAYLOAD + 1]));
        assertThrows(IllegalArgumentException.class, () -> new AbyssLinkFrame(0, 0L, new byte[0]));
        assertThrows(IllegalArgumentException.class, () -> new AbyssLinkFrame(8, 0L, new byte[0]));
        assertThrows(IllegalArgumentException.class, () -> new AbyssLinkFrame(254, 0L, new byte[0]));
        assertThrows(IllegalArgumentException.class, () -> new AbyssLinkFrame(-1, 3, 0L, new byte[0]));
        assertThrows(IllegalArgumentException.class, () -> new AbyssLinkFrame(256, 3, 0L, new byte[0]));
        assertThrows(NullPointerException.class, () -> new AbyssLinkFrame(3, 0L, null));
        assertThrows(IllegalArgumentException.class, () -> new AbyssLinkFrameCodec(MAX_PAYLOAD + 1));
        assertThrows(IllegalArgumentException.class, () -> new AbyssLinkFrameCodec(-1));
    }

    @Test
    void acceptsArbitraryMinorVersionsInBothDirections() {
        for (final int minor : new int[]{0, 1, 2, 255}) {
            final AbyssLinkFrame frame = new AbyssLinkFrame(minor, 3, 1L, new byte[0]);
            assertEquals(minor, frame.minor());
            final byte[] wire = encode(frame);
            assertEquals(minor, wire[MINOR_OFFSET] & 255, "minor on the wire");
            final EmbeddedChannel channel = new EmbeddedChannel(new AbyssLinkFrameCodec(MAX_PAYLOAD));
            try {
                assertTrue(channel.writeInbound(Unpooled.wrappedBuffer(wire)));
                final AbyssLinkFrame decoded = channel.readInbound();
                assertEquals(minor, decoded.minor());
                assertArrayEquals(wire, encode(decoded));
            } finally {
                releaseQuietly(channel);
            }
        }
    }

    @Test
    void rejectsOutOfRangeMinorVersionAtConstruction() {
        // The wire carries one minor byte, so the record's accepted range is exactly 0..255.
        assertThrows(IllegalArgumentException.class, () -> new AbyssLinkFrame(-1, 3, 0L, new byte[0]));
        assertThrows(IllegalArgumentException.class, () -> new AbyssLinkFrame(256, 3, 0L, new byte[0]));
        assertThrows(IllegalArgumentException.class, () -> new AbyssLinkFrame(Integer.MIN_VALUE, 3, 0L, new byte[0]));
        assertThrows(IllegalArgumentException.class, () -> new AbyssLinkFrame(Integer.MAX_VALUE, 3, 0L, new byte[0]));
    }

    @Test
    void defaultConstructorSelectsMinorZero() {
        assertEquals(0, new AbyssLinkFrame(3, 0L, new byte[0]).minor());
        assertEquals(0, encode(new AbyssLinkFrame(3, 0L, new byte[0]))[MINOR_OFFSET] & 255);
    }

    @Test
    void ownsPayloadBytesAtConstructionAndAccess() {
        final byte[] original = {1};
        final AbyssLinkFrame frame = new AbyssLinkFrame(3, 0L, original);
        original[0] = 2;
        assertEquals(1, frame.payload()[0]);
        final byte[] returned = frame.payload();
        returned[0] = 3;
        assertEquals(1, frame.payload()[0]);
        assertArrayEquals(new byte[]{1}, frame.payload());
    }

    @Test
    void decodedFramesDoNotSharePayloadStorage() {
        final byte[] wire = concat(
            encode(new AbyssLinkFrame(3, 1L, new byte[]{1})),
            encode(new AbyssLinkFrame(3, 2L, new byte[]{2})));
        final EmbeddedChannel channel = new EmbeddedChannel(new AbyssLinkFrameCodec(MAX_PAYLOAD));
        try {
            assertTrue(channel.writeInbound(Unpooled.wrappedBuffer(wire)));
            final AbyssLinkFrame first = channel.readInbound();
            final AbyssLinkFrame second = channel.readInbound();
            first.payload()[0] = 99;
            assertEquals(1, first.payload()[0]);
            assertEquals(2, second.payload()[0]);
        } finally {
            releaseQuietly(channel);
        }
    }

    @Test
    void roundTripsTlvPayloadThroughTheCodec() {
        final byte[] payload = AbyssLinkTlv.encode(Map.of(1, AbyssLinkTlv.value("BACKEND")));
        final EmbeddedChannel channel = new EmbeddedChannel(new AbyssLinkFrameCodec(MAX_PAYLOAD));
        try {
            assertTrue(channel.writeInbound(Unpooled.wrappedBuffer(encode(new AbyssLinkFrame(3, 9L, payload)))));
            final Map<Integer, byte[]> fields = AbyssLinkTlv.decode(channel.<AbyssLinkFrame>readInbound().payload());
            assertEquals("BACKEND", AbyssLinkTlv.string(fields, 1, 7));
        } finally {
            releaseQuietly(channel);
        }
    }

    // --- TLV --------------------------------------------------------------------------------

    @Test
    void decodesTlvInWireOrderAndRetainsUnknownIds() {
        final byte[] payload = new byte[]{
            1, 0, 5, 'B', 'A', 'C', 'K', 'E',
            (byte) 254, 0, 2, 7, 8,
            (byte) 200, 0, 0
        };
        final Map<Integer, byte[]> fields = AbyssLinkTlv.decode(payload);
        assertEquals(List.of(1, 254, 200), new ArrayList<>(fields.keySet()));
        assertEquals("BACKE", AbyssLinkTlv.string(fields, 1, 5));
        assertArrayEquals(new byte[]{7, 8}, fields.get(254));
        assertArrayEquals(new byte[0], fields.get(200));
        assertThrows(UnsupportedOperationException.class, () -> fields.put(2, new byte[0]));
    }

    @Test
    void decodedTlvValuesAreOwnedCopies() {
        final byte[] payload = {1, 0, 1, 9};
        final Map<Integer, byte[]> fields = AbyssLinkTlv.decode(payload);
        payload[3] = 7;
        assertEquals(9, fields.get(1)[0]);
    }

    @Test
    void rejectsDuplicateTlvIds() {
        assertThrows(IllegalArgumentException.class, () -> AbyssLinkTlv.decode(new byte[]{1, 0, 0, 1, 0, 0}));
        assertThrows(IllegalArgumentException.class, () -> AbyssLinkTlv.decode(new byte[]{1, 0, 0, 2, 0, 0, 1, 0, 0}));
    }

    @Test
    void rejectsTruncatedTlvHeadersAndValues() {
        assertThrows(IllegalArgumentException.class, () -> AbyssLinkTlv.decode(new byte[]{1}));
        assertThrows(IllegalArgumentException.class, () -> AbyssLinkTlv.decode(new byte[]{1, 0}));
        assertThrows(IllegalArgumentException.class, () -> AbyssLinkTlv.decode(new byte[]{1, 0, 2, 1}));
        assertThrows(IllegalArgumentException.class, () -> AbyssLinkTlv.decode(new byte[]{1, 0, 0, 2}));
        assertThrows(IllegalArgumentException.class, () -> AbyssLinkTlv.decode(new byte[]{1, 0, 0, 2, 0}));
        assertTrue(AbyssLinkTlv.decode(new byte[0]).isEmpty());
        assertEquals(0, AbyssLinkTlv.decode(new byte[]{1, 0, 0}).get(1).length);
    }

    @Test
    void boundsTlvPayloadsInBothDirections() {
        final byte[] largest = new byte[MAX_PAYLOAD - 3];
        final byte[] encoded = AbyssLinkTlv.encode(Map.of(1, largest));
        assertEquals(MAX_PAYLOAD, encoded.length);
        assertEquals(largest.length, AbyssLinkTlv.decode(encoded).get(1).length);
        assertThrows(IllegalArgumentException.class,
            () -> AbyssLinkTlv.encode(Map.of(1, new byte[MAX_PAYLOAD - 2])));
        assertThrows(IllegalArgumentException.class, () -> AbyssLinkTlv.decode(new byte[MAX_PAYLOAD + 1]));
        assertThrows(NullPointerException.class, () -> AbyssLinkTlv.decode(null));
        assertThrows(NullPointerException.class, () -> AbyssLinkTlv.encode(null));
    }

    @Test
    void rejectsTlvFieldsOutsideTheWireShape() {
        assertThrows(IllegalArgumentException.class, () -> AbyssLinkTlv.encode(Map.of(-1, new byte[]{1})));
        assertThrows(IllegalArgumentException.class, () -> AbyssLinkTlv.encode(Map.of(256, new byte[]{1})));
        assertThrows(IllegalArgumentException.class, () -> AbyssLinkTlv.encode(Map.of(1, new byte[65536])));
        final Map<Integer, byte[]> withNullValue = new LinkedHashMap<>();
        withNullValue.put(1, null);
        assertThrows(NullPointerException.class, () -> AbyssLinkTlv.encode(withNullValue));
        final byte[] encoded = AbyssLinkTlv.encode(Map.of(255, new byte[]{1}));
        assertArrayEquals(new byte[]{(byte) 255, 0, 1, 1}, encoded);
    }

    @Test
    void rejectsMalformedUtf8AndUnencodableStrings() {
        assertThrows(IllegalArgumentException.class,
            () -> AbyssLinkTlv.string(Map.of(1, new byte[]{(byte) 0xc0, (byte) 0x80}), 1, 10));
        assertThrows(IllegalArgumentException.class,
            () -> AbyssLinkTlv.string(Map.of(1, new byte[]{(byte) 0xed, (byte) 0xa0, (byte) 0x80}), 1, 10));
        assertThrows(IllegalArgumentException.class,
            () -> AbyssLinkTlv.string(Map.of(1, new byte[]{(byte) 0xe2, (byte) 0x82}), 1, 10));
        assertThrows(IllegalArgumentException.class,
            () -> AbyssLinkTlv.string(Map.of(1, new byte[]{(byte) 0xff}), 1, 10));
        assertThrows(IllegalArgumentException.class, () -> AbyssLinkTlv.value("\ud800"));
        assertThrows(IllegalArgumentException.class, () -> AbyssLinkTlv.value("\udc00"));
        assertThrows(NullPointerException.class, () -> AbyssLinkTlv.value((String) null));
    }

    @Test
    void boundsStrictUtf8ByEncodedBytes() {
        final byte[] eAcute = AbyssLinkTlv.value("é");
        assertEquals(2, eAcute.length);
        assertEquals("é", AbyssLinkTlv.string(Map.of(1, eAcute), 1, 2));
        assertThrows(IllegalArgumentException.class, () -> AbyssLinkTlv.string(Map.of(1, eAcute), 1, 1));
        assertThrows(IllegalArgumentException.class, () -> AbyssLinkTlv.string(Map.of(1, eAcute), 1, -1));
        assertThrows(IllegalArgumentException.class, () -> AbyssLinkTlv.string(Map.of(), 1, 10));
        final byte[] emoji = AbyssLinkTlv.value("\ud83d\ude00");
        assertEquals(4, emoji.length);
        assertEquals("\ud83d\ude00", AbyssLinkTlv.string(Map.of(1, emoji), 1, 4));
    }

    @Test
    void enforcesExactWidthsAndBooleanRange() {
        assertThrows(IllegalArgumentException.class, () -> AbyssLinkTlv.u8(Map.of(1, new byte[0]), 1));
        assertThrows(IllegalArgumentException.class, () -> AbyssLinkTlv.u8(Map.of(1, new byte[2]), 1));
        assertThrows(IllegalArgumentException.class, () -> AbyssLinkTlv.unsignedShort(Map.of(1, new byte[1]), 1));
        assertThrows(IllegalArgumentException.class, () -> AbyssLinkTlv.i32(Map.of(1, new byte[3]), 1));
        assertThrows(IllegalArgumentException.class, () -> AbyssLinkTlv.i64(Map.of(1, new byte[4]), 1));
        assertThrows(IllegalArgumentException.class, () -> AbyssLinkTlv.uuid(Map.of(1, new byte[15]), 1));
        assertThrows(IllegalArgumentException.class, () -> AbyssLinkTlv.uuid(Map.of(1, new byte[17]), 1));
        assertThrows(IllegalArgumentException.class, () -> AbyssLinkTlv.bool(Map.of(1, new byte[2]), 1));
        assertThrows(IllegalArgumentException.class, () -> AbyssLinkTlv.bool(Map.of(1, new byte[]{2}), 1));
        assertThrows(IllegalArgumentException.class, () -> AbyssLinkTlv.bool(Map.of(1, new byte[]{(byte) 255}), 1));
        assertThrows(IllegalArgumentException.class, () -> AbyssLinkTlv.i32(Map.of(), 1));
        assertThrows(IllegalArgumentException.class, () -> AbyssLinkTlv.u8(256));
        assertThrows(IllegalArgumentException.class, () -> AbyssLinkTlv.u8(-1));
        assertThrows(IllegalArgumentException.class, () -> AbyssLinkTlv.u16(65536));
        assertThrows(IllegalArgumentException.class, () -> AbyssLinkTlv.u16(-1));
        assertArrayEquals(new byte[]{(byte) 255}, AbyssLinkTlv.u8(255));
        assertArrayEquals(new byte[]{(byte) 0xff, (byte) 0xff}, AbyssLinkTlv.u16(65535));
        assertFalse(AbyssLinkTlv.bool(Map.of(1, new byte[]{0}), 1));
        assertTrue(AbyssLinkTlv.bool(Map.of(1, new byte[]{1}), 1));
    }

    @Test
    void roundTripsEveryScalarHelper() {
        final UUID uuid = UUID.fromString("12345678-9abc-def0-1234-56789abcdef0");
        final Map<Integer, byte[]> values = new LinkedHashMap<>();
        values.put(1, AbyssLinkTlv.value("BACKEND"));
        values.put(2, AbyssLinkTlv.value(-123));
        values.put(3, AbyssLinkTlv.value(Long.MIN_VALUE));
        values.put(4, AbyssLinkTlv.value(true));
        values.put(5, AbyssLinkTlv.value(false));
        values.put(6, AbyssLinkTlv.value(uuid));
        values.put(7, AbyssLinkTlv.u16(65535));
        values.put(8, AbyssLinkTlv.u8(255));
        values.put(254, new byte[]{7});

        final Map<Integer, byte[]> decoded = AbyssLinkTlv.decode(AbyssLinkTlv.encode(values));
        assertEquals("BACKEND", AbyssLinkTlv.string(decoded, 1, 7));
        assertEquals(-123, AbyssLinkTlv.i32(decoded, 2));
        assertEquals(Long.MIN_VALUE, AbyssLinkTlv.i64(decoded, 3));
        assertTrue(AbyssLinkTlv.bool(decoded, 4));
        assertFalse(AbyssLinkTlv.bool(decoded, 5));
        assertEquals(uuid, AbyssLinkTlv.uuid(decoded, 6));
        assertEquals(65535, AbyssLinkTlv.unsignedShort(decoded, 7));
        assertEquals(255, AbyssLinkTlv.u8(decoded, 8));
        assertArrayEquals(new byte[]{7}, AbyssLinkTlv.bytes(decoded, 254, 1));
        assertThrows(IllegalArgumentException.class, () -> AbyssLinkTlv.bytes(decoded, 254, 2));
        assertEquals(values.size(), decoded.size());
    }

    // --- helpers ----------------------------------------------------------------------------

    private static byte[] encode(final AbyssLinkFrame frame) {
        return encode(frame, MAX_PAYLOAD);
    }

    private static byte[] encode(final AbyssLinkFrame frame, final int limit) {
        final EmbeddedChannel channel = new EmbeddedChannel(new AbyssLinkFrameCodec(limit));
        try {
            assertTrue(channel.writeOutbound(frame));
            final ByteBuf out = channel.readOutbound();
            try {
                final byte[] bytes = new byte[out.readableBytes()];
                out.readBytes(bytes);
                return bytes;
            } finally {
                out.release();
            }
        } finally {
            releaseQuietly(channel);
        }
    }

    private static void assertInboundRejected(final Class<? extends Throwable> expected, final byte[] wire, final int limit) {
        final EmbeddedChannel channel = new EmbeddedChannel(new AbyssLinkFrameCodec(limit));
        try {
            final RuntimeException thrown = assertThrows(RuntimeException.class,
                () -> channel.writeInbound(Unpooled.wrappedBuffer(wire)), "inbound must be rejected");
            assertInstanceOf(expected, thrown, "pipeline must surface the codec exception");
            assertHasCause(expected, thrown);
        } finally {
            releaseQuietly(channel);
        }
    }

    private static void assertOutboundRejected(final Class<? extends Throwable> expected, final AbyssLinkFrame frame, final int limit) {
        final EmbeddedChannel channel = new EmbeddedChannel(new AbyssLinkFrameCodec(limit));
        try {
            final RuntimeException thrown = assertThrows(RuntimeException.class,
                () -> channel.writeOutbound(frame), "outbound must be rejected");
            // MessageToByteEncoder wraps a throwing encode in EncoderException; assert the reason.
            assertHasCause(expected, thrown);
        } finally {
            releaseQuietly(channel);
        }
    }

    private static void assertHasCause(final Class<? extends Throwable> expected, final Throwable thrown) {
        Throwable current = thrown;
        while (current != null) {
            if (expected.isInstance(current)) {
                return;
            }
            current = current.getCause();
        }
        fail("Expected " + expected.getSimpleName() + " but got " + thrown, thrown);
    }

    private static void releaseQuietly(final EmbeddedChannel channel) {
        try {
            channel.finishAndReleaseAll();
        } catch (RuntimeException expected) {
            // The recorded decode failure is the behaviour under test.
        }
    }

    private static byte[] goldenWith(final int offset, final byte... value) {
        final byte[] copy = HEARTBEAT_GOLDEN.clone();
        System.arraycopy(value, 0, copy, offset, value.length);
        return copy;
    }

    private static byte[] concat(final byte[]... parts) {
        int length = 0;
        for (final byte[] part : parts) {
            length += part.length;
        }
        final byte[] joined = new byte[length];
        int offset = 0;
        for (final byte[] part : parts) {
            System.arraycopy(part, 0, joined, offset, part.length);
            offset += part.length;
        }
        return joined;
    }

    private static void reverse(final byte[] bytes, final int from, final int to) {
        for (int i = from, j = to - 1; i < j; i++, j--) {
            final byte swap = bytes[i];
            bytes[i] = bytes[j];
            bytes[j] = swap;
        }
    }
}
