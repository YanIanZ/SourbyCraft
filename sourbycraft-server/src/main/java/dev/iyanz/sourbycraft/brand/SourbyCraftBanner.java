package dev.iyanz.sourbycraft.brand;

public final class SourbyCraftBanner {

    private SourbyCraftBanner() {}

    public static String render(BuildInfo info) {
        StringBuilder sb = new StringBuilder();
        sb.append("\n");
        sb.append("   ╔══════════════════════════════════════════════════════════╗\n");
        sb.append("   ║                                                          ║\n");
        sb.append(String.format("   ║   ⚡  SOURBYCRAFT  ⚡   ·  %-30s ║%n",
            info.version()));
        sb.append("   ║                                                          ║\n");
        sb.append(String.format("   ║   %-54s ║%n", info.tagline()));
        sb.append("   ║                                                          ║\n");
        sb.append(String.format("   ║   Paper %s  ·  Java %-30s ║%n",
            info.mcVersion(),
            System.getProperty("java.specification.version")));
        sb.append("   ║                                                          ║\n");
        sb.append("   ╚══════════════════════════════════════════════════════════╝\n");
        return sb.toString();
    }
}
