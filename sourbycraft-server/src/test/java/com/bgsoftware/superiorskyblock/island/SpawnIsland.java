package com.bgsoftware.superiorskyblock.island;

/**
 * Test stand-in with the audited class name only, so a lambda declared here has the
 * {@code SpawnIsland$$Lambda} shape {@code SuperiorSpawnTaskOwner} recognizes. Its bytes differ
 * from the audited 2026.3 class, so owner resolution must refuse it.
 */
public final class SpawnIsland {

    /** A single-capture instance callback, the shape of the audited constructor callback. */
    public Runnable callback() {
        return () -> this.hashCode();
    }
}
