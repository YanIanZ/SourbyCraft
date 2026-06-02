package dev.iyanz.sourbycraft.brand;

public final class SourbyCraftBanner {

    private SourbyCraftBanner() {}

    public static String render(BuildInfo info) {
        String variant = info.isPvp() ? "PVP" : "NORMAL";
        StringBuilder sb = new StringBuilder();
        sb.append("\n");
        sb.append("   ╔══════════════════════════════════════════════════════════╗\n");
        sb.append("   ║                                                          ║\n");
        sb.append(String.format("   ║   ⚡  SOURBYCRAFT  ⚡   ·  %-7s  ·  %-7s         ║%n",
            info.version(), variant));
        sb.append("   ║                                                          ║\n");
        sb.append(String.format("   ║   %-54s ║%n", info.tagline()));
        sb.append("   ║                                                          ║\n");
        sb.append(String.format("   ║   Paper %s  ·  Java %s  ·  Variant: %-8s ║%n",
            info.mcVersion(),
            System.getProperty("java.specification.version"),
            variant));
        if (info.isPvp()) {
            sb.append("   ║   PvP-tuned defaults active                              ║\n");
        }
        sb.append("   ║                                                          ║\n");
        sb.append("   ╚══════════════════════════════════════════════════════════╝\n");
        return sb.toString();
    }
}
