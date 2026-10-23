package dev.iyanz.sourbycraft.abysslink;

import io.netty.buffer.ByteBuf;
import io.netty.channel.ChannelHandlerContext;
import io.netty.handler.codec.ByteToMessageCodec;
import io.netty.handler.codec.CorruptedFrameException;
import io.netty.handler.codec.TooLongFrameException;
import java.util.List;

/**
 * Per-connection AbyssLink v1 frame codec.
 *
 * <p>Wire header (20 bytes, big endian): {@code ABYL} magic {@code 0x4142594c}, major {@code 1},
 * a raw minor byte, message type {@code 1..7} or {@code 255}, flags {@code 0}, an unsigned 32-bit
 * payload length of at most {@link AbyssLinkFrame#MAX_PAYLOAD}, and an opaque 64-bit request ID.
 * The whole fixed header is validated — magic, major, type, flags and the declared length — before
 * a payload array is allocated, so an oversize or unsupported declaration is rejected by its header
 * alone.</p>
 *
 * <p>The minor byte is not filtered here: it is decoded as an unsigned byte and written back from
 * {@link AbyssLinkFrame#minor()} unchanged, preserving Leviathan's compatibility contract that the
 * codec transports every {@code 0..255} minor. Compatibility policy lives above the codec:
 * {@link AbyssLinkSession} currently rejects any non-zero minor as unnegotiated.</p>
 *
 * <p>Fragmentation and coalescing follow Netty's decoder contract: an incomplete frame stays in
 * the cumulation buffer without being consumed, and every complete frame in one read is emitted
 * in order. Outbound bounds are symmetric with inbound bounds: a payload above the configured
 * limit is refused before any byte is written.</p>
 */
public final class AbyssLinkFrameCodec extends ByteToMessageCodec<AbyssLinkFrame> {

    public static final int HEADER_SIZE = 20;
    public static final int MAGIC = 0x4142594c;
    public static final int MAJOR = 1;

    private final int maxPayload;

    public AbyssLinkFrameCodec(int maxPayload) {
        if (maxPayload < 0 || maxPayload > AbyssLinkFrame.MAX_PAYLOAD) {
            throw new IllegalArgumentException("Invalid payload limit");
        }
        this.maxPayload = maxPayload;
    }

    @Override
    protected void decode(ChannelHandlerContext ctx, ByteBuf in, List<Object> out) {
        if (in.readableBytes() < HEADER_SIZE) {
            return;
        }
        final int start = in.readerIndex();
        if (in.getInt(start) != MAGIC) {
            throw new CorruptedFrameException("Invalid AbyssLink magic");
        }
        if (in.getUnsignedByte(start + 4) != MAJOR) {
            throw new CorruptedFrameException("Unsupported AbyssLink major version");
        }
        final int minor = in.getUnsignedByte(start + 5);
        final int type = in.getUnsignedByte(start + 6);
        if (!AbyssLinkFrame.validType(type)) {
            throw new CorruptedFrameException("Unknown AbyssLink message type");
        }
        if (in.getUnsignedByte(start + 7) != 0) {
            throw new CorruptedFrameException("Unsupported AbyssLink flags");
        }
        // Unsigned read: a peer cannot wrap a huge declaration into the configured limit.
        final long length = in.getUnsignedInt(start + 8);
        if (length > maxPayload) {
            throw new TooLongFrameException("AbyssLink payload exceeds limit");
        }
        if (in.readableBytes() - HEADER_SIZE < length) {
            return;
        }
        final long requestId = in.getLong(start + 12);
        in.skipBytes(HEADER_SIZE);
        final byte[] payload = new byte[(int) length];
        in.readBytes(payload);
        out.add(new AbyssLinkFrame(minor, type, requestId, payload));
    }

    @Override
    protected void encode(ChannelHandlerContext ctx, AbyssLinkFrame frame, ByteBuf out) {
        final byte[] payload = frame.wirePayload();
        if (payload.length > maxPayload) {
            throw new TooLongFrameException("AbyssLink payload exceeds limit");
        }
        out.writeInt(MAGIC)
            .writeByte(MAJOR)
            .writeByte(frame.minor())
            .writeByte(frame.type())
            .writeByte(0)
            .writeInt(payload.length)
            .writeLong(frame.requestId())
            .writeBytes(payload);
    }
}
