package dev.iyanz.sourbycraft.entity.teleport;

import java.io.IOException;

/** Shares the patch-reading helpers of {@link OwnedTeleportPatchShapeTest} with other packages' shape tests. */
public final class OwnedTeleportPatchShapeTestAccess {

    private OwnedTeleportPatchShapeTestAccess() {}

    public static String patch(final String featureDir, final String nameContains) throws IOException {
        return OwnedTeleportPatchShapeTest.patch(featureDir, nameContains);
    }

    public static String file(final String patch, final String path) {
        return OwnedTeleportPatchShapeTest.file(patch, path);
    }

    public static int at(final String text, final String needle) {
        return OwnedTeleportPatchShapeTest.at(text, needle);
    }

    public static int removed(final String section) {
        return OwnedTeleportPatchShapeTest.removed(section);
    }
}
