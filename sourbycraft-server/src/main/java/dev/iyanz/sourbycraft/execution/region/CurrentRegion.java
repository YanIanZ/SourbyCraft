package dev.iyanz.sourbycraft.execution.region;

/**
 * "Which region is this thread ticking?", as the Aurora Bridge needs it.
 * {@link FoliaRegionBackend} supplies it from the current engine.
 */
public interface CurrentRegion {

    /** A chunk owned by the calling thread's region, or {@code null} when it is ticking none. */
    RegionAnchor anchor();
}
