package dev.iyanz.sourbycraft.swm.plugin.loader;

import java.io.*;
import java.nio.file.*;
import java.util.*;

public interface SlimeLoader {
    /** Read raw bytes for a slime world */
    byte[] readWorld(String name) throws IOException;

    /** Write raw bytes for a slime world */
    void writeWorld(String name, byte[] data) throws IOException;

    /** Delete a slime world */
    void deleteWorld(String name) throws IOException;

    /** List all slime world names */
    List<String> listWorlds() throws IOException;

    /** Check if a world exists */
    default boolean worldExists(String name) {
        try { return listWorlds().contains(name); }
        catch (IOException e) { return false; }
    }
}
