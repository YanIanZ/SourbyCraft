package dev.iyanz.sourbycraft.api.profile;

import java.util.List;
import org.jspecify.annotations.Nullable;

/**
 * One page of a co-op listing (Build 52). A page holds at most {@value #MAX_PAGE_SIZE} entries; the
 * server chooses the size. Pass {@link #nextCursor()} back as the cursor of the same listing to continue;
 * it is opaque, bound to that listing and scope, and may be refused with
 * {@link ProfileErrorCode#INVALID_REQUEST} when the underlying index changed, in which case the listing
 * restarts from a {@code null} cursor. A listing never answers "empty" for an index it could not read: it
 * fails instead.
 *
 * @param items the entries of this page
 * @param nextCursor the cursor of the next page, or {@code null} when this was the last page
 * @param <T> the entry type
 */
public record CoopPage<T>(List<T> items, @Nullable String nextCursor) {

    /** Upper bound of a page. */
    public static final int MAX_PAGE_SIZE = 32;

    public CoopPage {
        items = List.copyOf(items);
        if (items.size() > MAX_PAGE_SIZE) throw new IllegalArgumentException("page of " + items.size());
    }
}
