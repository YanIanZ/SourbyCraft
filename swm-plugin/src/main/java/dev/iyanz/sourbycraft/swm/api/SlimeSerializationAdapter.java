package dev.iyanz.sourbycraft.swm.api;

import dev.iyanz.sourbycraft.swm.api.exceptions.CorruptedWorldException;
import dev.iyanz.sourbycraft.swm.api.exceptions.NewerFormatException;
import org.jetbrains.annotations.Nullable;
import java.io.IOException;

public interface SlimeSerializationAdapter {
    byte[] serialize(SlimeWorld world) throws IOException;
    SlimeWorld deserialize(String worldName, byte[] data, @Nullable SlimeLoader loader,
                           SlimePropertyMap propertyMap, boolean readOnly)
            throws CorruptedWorldException, NewerFormatException, IOException;
    int getSlimeFormat();
}
