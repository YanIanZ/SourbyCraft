package dev.iyanz.sourbycraft.swm.core.reader;

import dev.iyanz.sourbycraft.swm.api.*;
import dev.iyanz.sourbycraft.swm.api.exceptions.*;
import java.io.*;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

public final class SlimeWorldReaderRegistry {
    private static final Map<Byte, VersionedByteSlimeWorldReader<SlimeWorld>> readers = new ConcurrentHashMap<>();

    static {
        register((byte) 0x0D, new v13SlimeWorldReader());
    }

    public static void register(byte version, VersionedByteSlimeWorldReader<SlimeWorld> reader) {
        readers.put(version, reader);
    }

    public static SlimeWorld readWorld(SlimeLoader loader, String worldName, byte[] data,
                                       SlimePropertyMap propertyMap, boolean readOnly)
            throws CorruptedWorldException, NewerFormatException, IOException {
        DataInputStream in = new DataInputStream(new ByteArrayInputStream(data));
        byte[] header = new byte[2];
        in.readFully(header);
        if (header[0] != (byte)0xB1 || header[1] != (byte)0x0B) {
            throw new CorruptedWorldException("Invalid SLIME magic bytes: " +
                String.format("%02X %02X", header[0], header[1]));
        }
        byte version = in.readByte();
        VersionedByteSlimeWorldReader<SlimeWorld> reader = readers.get(version);
        if (reader == null) {
            throw new NewerFormatException("Unsupported SRF version: " + (version & 0xFF));
        }
        return reader.deserialize(loader, worldName, data, propertyMap, readOnly);
    }

    private SlimeWorldReaderRegistry() {}
}
