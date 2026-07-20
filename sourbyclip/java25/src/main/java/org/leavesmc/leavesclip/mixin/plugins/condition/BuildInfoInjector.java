package org.leavesmc.leavesclip.mixin.plugins.condition;

import dev.iyanz.sourbyclip.update.AutoUpdate;
import org.leavesmc.leavesclip.logger.Logger;
import org.leavesmc.leavesclip.logger.SimpleLogger;
import org.leavesmc.plugin.mixin.condition.BuildInfoProvider;
import org.leavesmc.plugin.mixin.condition.data.BuildInfo;
import org.leavesmc.plugin.mixin.condition.data.MinecraftVersionData;

import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.Properties;

/**
 * Feeds the {@code leaves-plugin-mixin-condition} runtime the build metadata it uses to evaluate
 * conditional mixins ({@code @MinecraftVersion} / {@code @ServerBuild}).
 *
 * <p><b>SourbyCraft/Cherry fix.</b> Upstream Leavesclip hard-reads {@code /META-INF/build-info}
 * (a LeavesMC-specific resource) and NPE-crashes the whole mixin bootstrap if it is missing — which
 * it always is on this base, because SourbyCraft is built on Canvas (weaver), not Leaves. That NPE
 * made the entire native mixin path non-functional. Cherry makes this resilient:
 * <ol>
 *   <li>use Leaves' {@code /META-INF/build-info} if present (tab-separated
 *       {@code project\tmcVersion\tbuild}), else</li>
 *   <li>synthesize {@link BuildInfo} from SourbyCraft's own
 *       {@code /META-INF/sourbycraft-build.properties} (project "SourbyCraft", the real
 *       {@code mcVersion}, and the leading digits of {@code build} e.g. {@code 4c} -> 4), else</li>
 *   <li>fall back to a harmless default so {@link BuildInfoProvider} is never left null (which
 *       would re-introduce the crash inside the condition checker).</li>
 * </ol>
 */
public class BuildInfoInjector {
    private static final Logger logger = new SimpleLogger("BuildInfoInjector");

    public static void inject() {
        BuildInfo buildInfo = fromLeavesBuildInfo();
        if (buildInfo == null) {
            buildInfo = fromSourbyCraftProperties();
        }
        if (buildInfo == null) {
            buildInfo = fromPaperclipVersionJson();
        }
        if (buildInfo == null) {
            logger.warn("Cherry: no build-info found (Leaves /META-INF/build-info, " +
                "/META-INF/sourbycraft-build.properties, nor version.json); conditional mixins will see a placeholder build.");
            buildInfo = new BuildInfo("SourbyCraft", MinecraftVersionData.minecraftVersion(0, 0, 0), 0);
        }
        logger.info(buildInfo.toString());
        BuildInfoProvider.INSTANCE.setBuildInfo(buildInfo);
    }

    private static BuildInfo fromLeavesBuildInfo() {
        try (InputStream in = AutoUpdate.getResourceAsStreamFromTargetJar("/META-INF/build-info")) {
            if (in == null) {
                return null;
            }
            String s = new String(in.readAllBytes(), StandardCharsets.UTF_8);
            if (s.endsWith("\tDEV")) {
                s = s.replaceFirst("\tDEV", "\t0");
            }
            return BuildInfo.fromString(s);
        } catch (Throwable t) {
            logger.warn("Cherry: /META-INF/build-info present but unparseable, falling back", t);
            return null;
        }
    }

    private static BuildInfo fromSourbyCraftProperties() {
        try (InputStream in = AutoUpdate.getResourceAsStreamFromTargetJar("/META-INF/sourbycraft-build.properties")) {
            if (in == null) {
                return null;
            }
            Properties props = new Properties();
            props.load(in);
            String mcVersion = props.getProperty("mcVersion", "0.0.0").trim();
            String rawBuild = props.getProperty("build", "0").trim(); // e.g. "4c"
            int buildNumber = leadingDigits(rawBuild);
            MinecraftVersionData mc;
            try {
                mc = MinecraftVersionData.fromString(mcVersion);
            } catch (Throwable t) {
                mc = MinecraftVersionData.minecraftVersion(0, 0, 0);
            }
            logger.info("Cherry: synthesized build-info from sourbycraft-build.properties " +
                "(SourbyCraft {} build {})", mcVersion, buildNumber);
            return new BuildInfo("SourbyCraft", mc, buildNumber);
        } catch (Throwable t) {
            logger.warn("Cherry: failed reading /META-INF/sourbycraft-build.properties", t);
            return null;
        }
    }

    /**
     * Last-resort source that IS reachable on SourbyClip's own classloader: the paperclip
     * {@code version.json} at the outer-jar root (the SourbyCraft build properties live in the
     * nested versioned server jar, which isn't on the classpath this early). Gives the real
     * Minecraft version for {@code @MinecraftVersion} conditions; build number stays 0 (unknown).
     */
    private static BuildInfo fromPaperclipVersionJson() {
        try (InputStream in = AutoUpdate.getResourceAsStreamFromTargetJar("/version.json")) {
            if (in == null) {
                return null;
            }
            String json = new String(in.readAllBytes(), StandardCharsets.UTF_8);
            String id = extractJsonString(json, "id");
            if (id == null) {
                id = extractJsonString(json, "name");
            }
            if (id == null) {
                return null;
            }
            MinecraftVersionData mc = MinecraftVersionData.fromString(id.trim());
            logger.info("Cherry: synthesized build-info from paperclip version.json (Minecraft {})", id.trim());
            return new BuildInfo("SourbyCraft", mc, 0);
        } catch (Throwable t) {
            logger.warn("Cherry: failed reading /version.json", t);
            return null;
        }
    }

    /** Minimal, dependency-free extraction of a top-level {@code "key":"value"} string. */
    private static String extractJsonString(String json, String key) {
        int k = json.indexOf('"' + key + '"');
        if (k < 0) {
            return null;
        }
        int colon = json.indexOf(':', k + key.length() + 2);
        if (colon < 0) {
            return null;
        }
        int firstQuote = json.indexOf('"', colon + 1);
        if (firstQuote < 0) {
            return null;
        }
        int secondQuote = json.indexOf('"', firstQuote + 1);
        if (secondQuote < 0) {
            return null;
        }
        return json.substring(firstQuote + 1, secondQuote);
    }

    private static int leadingDigits(String s) {
        int i = 0;
        while (i < s.length() && Character.isDigit(s.charAt(i))) {
            i++;
        }
        if (i == 0) {
            return 0;
        }
        try {
            return Integer.parseInt(s.substring(0, i));
        } catch (NumberFormatException e) {
            return 0;
        }
    }
}
