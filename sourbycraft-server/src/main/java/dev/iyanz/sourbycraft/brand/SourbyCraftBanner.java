package dev.iyanz.sourbycraft.brand;

public final class SourbyCraftBanner {

    private SourbyCraftBanner() {}

    public static String render(BuildInfo info) {
        final String java = System.getProperty("java.specification.version");
        final int cores = Runtime.getRuntime().availableProcessors();
        StringBuilder sb = new StringBuilder();
        sb.append('\n');
        sb.append("   ┌─ SOURBYCRAFT ").append("─".repeat(Math.max(0, 44 - info.version().length())))
          .append(' ').append(info.version()).append(" ─┐\n");
        sb.append("   │  ").append(pad(info.tagline(), 58)).append("│\n");
        sb.append("   │  ").append(pad("survival · self-tuning perf · anti-xray · 150+ ready", 58)).append("│\n");
        sb.append("   │  ").append(pad("Paper " + info.mcVersion() + "  ·  Java " + java + "  ·  " + cores + " cores", 58)).append("│\n");
        sb.append("   └").append("─".repeat(62)).append("┘\n");
        return sb.toString();
    }

    private static String pad(String s, int width) {
        if (s.length() >= width) return s.substring(0, width);
        return s + " ".repeat(width - s.length());
    }
}
