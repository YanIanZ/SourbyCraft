package dev.iyanz.sourbycraft.update;

import java.util.ArrayList;
import java.util.List;

/**
 * Minimal, channel-agnostic version comparator for SourbyCraft release tags (F1-8).
 *
 * <p>SourbyCraft versions look like {@code 26.2-REL}, {@code 26.2-1.3-DEV}, {@code 26.11-EXP}.
 * The channel suffix is stripped by the updater before comparison (it is compared separately —
 * see {@link UpdateChannel}); what remains is a dotted numeric core ({@code 26.2}, {@code 26.2.1.3},
 * {@code 26.11}) that we compare component-by-component, numerically, so {@code 26.11 > 26.2}
 * (which a naive string compare gets wrong).
 *
 * <p>Non-numeric components are compared lexically as a tie-break, and a longer version with extra
 * trailing components is considered newer when all shared components are equal ({@code 26.2.1 > 26.2}).
 * This is intentionally small — SourbyCraft tags are simple — and never throws.
 */
public final class SemVer {

    private SemVer() {}

    /**
     * Strip a trailing channel suffix ({@code -REL}/{@code -DEV}/{@code -EXP}, any case) and a
     * leading {@code v}, returning the numeric core used for ordering. e.g. {@code "v26.2-REL"} -> {@code "26.2"}.
     */
    public static String stripChannel(String version) {
        if (version == null) return "";
        String v = version.trim();
        if (v.regionMatches(true, 0, "v", 0, 1)) v = v.substring(1);
        int dash = v.lastIndexOf('-');
        if (dash > 0) {
            String suffix = v.substring(dash + 1).trim();
            if (suffix.equalsIgnoreCase("REL") || suffix.equalsIgnoreCase("DEV") || suffix.equalsIgnoreCase("EXP")) {
                v = v.substring(0, dash);
            }
        }
        return v.trim();
    }

    /**
     * Compare two SourbyCraft version cores (channel already irrelevant/stripped). Returns
     * {@code <0} if {@code a} is older, {@code 0} if equal, {@code >0} if {@code a} is newer.
     * Robust to a {@code v} prefix and to a channel suffix still being present.
     */
    public static int compare(String a, String b) {
        List<Comparable<?>> pa = tokenize(stripChannel(a));
        List<Comparable<?>> pb = tokenize(stripChannel(b));
        int n = Math.max(pa.size(), pb.size());
        for (int i = 0; i < n; i++) {
            Comparable<?> ca = i < pa.size() ? pa.get(i) : Integer.valueOf(0);
            Comparable<?> cb = i < pb.size() ? pb.get(i) : Integer.valueOf(0);
            int c = compareComponent(ca, cb);
            if (c != 0) return c;
        }
        return 0;
    }

    /** True when {@code candidate} is strictly newer than {@code current}. */
    public static boolean isNewer(String candidate, String current) {
        return compare(candidate, current) > 0;
    }

    private static int compareComponent(Comparable<?> a, Comparable<?> b) {
        if (a instanceof Integer ia && b instanceof Integer ib) return Integer.compare(ia, ib);
        // A numeric component ranks above an alpha component of the same position.
        if (a instanceof Integer && b instanceof String) return 1;
        if (a instanceof String && b instanceof Integer) return -1;
        return String.valueOf(a).compareToIgnoreCase(String.valueOf(b));
    }

    /**
     * Split a numeric-core version into a list of Integer (when numeric) / String components.
     *
     * <p>Splits on both {@code .} and {@code -} so a DEV build's numeric core keeps decomposing
     * fully. A DEV version is {@code <major>-<releaseVersionFull>-DEV} (Gradle
     * {@code sourbycraftSuffixProvider}), e.g. {@code 26.2-1.3-DEV}; after {@link #stripChannel}
     * removes the {@code -DEV} the core is {@code 26.2-1.3}, whose middle {@code 2-1} still carries
     * the internal {@code -} separator. Splitting on {@code .} alone would leave {@code "2-1"} as a
     * non-numeric String component, which then compares LEXICALLY — so {@code 26.10-1.0} would sort
     * BELOW {@code 26.9-1.0} ({@code "10-1" < "9-1"}), a wrong-sign compare that made the DEV-channel
     * updater miss a newer release across a minor bump. Splitting on {@code -} too yields the correct
     * all-numeric component list {@code [26, 2, 1, 3]}.
     */
    private static List<Comparable<?>> tokenize(String core) {
        List<Comparable<?>> out = new ArrayList<>();
        if (core == null || core.isBlank()) return out;
        for (String seg : core.split("[.-]")) {
            if (seg.isBlank()) continue;
            try {
                out.add(Integer.valueOf(Integer.parseInt(seg.trim())));
            } catch (NumberFormatException ex) {
                out.add(seg.trim());
            }
        }
        return out;
    }
}
