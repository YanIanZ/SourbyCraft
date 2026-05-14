package dev.iyanz.sourbycraft.swm.core.reader;

import dev.iyanz.sourbycraft.swm.api.*;
import dev.iyanz.sourbycraft.swm.api.exceptions.*;
import java.io.IOException;

public interface VersionedByteSlimeWorldReader<T extends SlimeWorld> {
    T deserialize(SlimeLoader loader, String worldName, byte[] serializedWorld,
                  SlimePropertyMap propertyMap, boolean readOnly)
            throws CorruptedWorldException, NewerFormatException, IOException;
}
