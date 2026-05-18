package dev.iyanz.sourbycraft.swm.api;

import java.io.IOException;
import java.util.List;

public interface SlimeLoader {
    byte[] readWorld(String worldName) throws IOException;
    boolean worldExists(String worldName) throws IOException;
    List<String> listWorlds() throws IOException;
    void saveWorld(String worldName, byte[] serializedWorld) throws IOException;
    void deleteWorld(String worldName) throws IOException;
}
