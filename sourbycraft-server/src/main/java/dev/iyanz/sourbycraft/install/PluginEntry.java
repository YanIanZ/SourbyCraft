package dev.iyanz.sourbycraft.install;

public record PluginEntry(
    String name,
    String source,
    String repo,
    String url,
    String assetGlob,
    String sha256
) {
    public boolean isGithub() { return "github".equalsIgnoreCase(source); }
    public boolean isCi() { return "ci".equalsIgnoreCase(source); }
    public boolean isJenkins() { return "jenkins".equalsIgnoreCase(source); }
}
