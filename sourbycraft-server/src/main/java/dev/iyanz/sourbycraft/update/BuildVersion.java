package dev.iyanz.sourbycraft.update;

import java.util.ArrayList;
import java.util.List;

/**
 * Composite build-version comparator for the SourbyCraft auto-updater.
 *
 * <p>Handles version strings that go beyond plain integers: {@code "43"}, {@code "43.1"},
 * {@code "44-hotfix"}, {@code "44.2-hotfix1"}. Tokenizes on {@code .} and {@code -}, compares
 * numerically where possible, lexically otherwise. A version with more components is newer when
 * all shared components are equal ({@code 43.1 > 43}, {@code 44-hotfix > 44}).
 */
public record BuildVersion(String raw, List<Comparable<?>> tokens) implements Comparable<BuildVersion> {

    public static final BuildVersion UNKNOWN = new BuildVersion("", List.of());

    /**
     * Parse a raw build-version string into comparable tokens. Returns {@link #UNKNOWN} for
     * null/blank input. The raw string is preserved for display; tokens drive ordering.
     */
    public static BuildVersion parse(String raw) {
        if (raw == null || raw.isBlank()) return UNKNOWN;
        List<Comparable<?>> out = new ArrayList<>();
        for (String seg : raw.split("[.-]")) {
            if (seg.isBlank()) continue;
            try {
                out.add(Integer.parseInt(seg.trim()));
            } catch (NumberFormatException ex) {
                out.add(seg.trim());
            }
        }
        return new BuildVersion(raw.trim(), List.copyOf(out));
    }

    public boolean isUnknown() {
        return tokens.isEmpty();
    }

    @Override
    public int compareTo(BuildVersion other) {
        int shared = Math.min(tokens.size(), other.tokens.size());
        for (int i = 0; i < shared; i++) {
            int c = cmp(tokens.get(i), other.tokens.get(i));
            if (c != 0) return c;
        }
        return Integer.compare(tokens.size(), other.tokens.size());
    }

    private static int cmp(Comparable<?> a, Comparable<?> b) {
        if (a instanceof Integer ia && b instanceof Integer ib) return Integer.compare(ia, ib);
        if (a instanceof Integer) return 1;
        if (b instanceof Integer) return -1;
        return String.valueOf(a).compareToIgnoreCase(String.valueOf(b));
    }

    @Override
    public String toString() {
        return raw;
    }
}
