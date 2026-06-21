package dev.iyanz.sourbycraft.nms;

import java.io.IOException;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Reads nms-compat-result.json produced by sanity-harness-plugin and emits JUnit XML.
 * Args: <variantLabel> <resultJsonPath> <junitXmlPath>
 */
public final class CompatHarness {

    public static void main(String[] args) throws Exception {
        if (args.length != 3) {
            System.err.println("Usage: CompatHarness <variantLabel> <resultJsonPath> <junitXmlPath>");
            System.exit(2);
        }
        String variant = args[0];
        Path resultJson = Paths.get(args[1]);
        Path junitXml = Paths.get(args[2]);

        if (!Files.exists(resultJson)) {
            System.err.println("CompatHarness: missing " + resultJson);
            System.exit(3);
        }

        String json = Files.readString(resultJson, StandardCharsets.UTF_8);
        List<TestCase> cases = parseRows(variant, json);

        Files.createDirectories(junitXml.getParent());
        try (OutputStream os = Files.newOutputStream(junitXml)) {
            writeJunit(os, variant, cases);
        }

        int failures = (int) cases.stream().filter(c -> !c.passed).count();
        System.out.println("CompatHarness[" + variant + "]: " + cases.size()
            + " tests, " + failures + " failures");
        System.exit(0);
    }

    static List<TestCase> parseRows(String variant, String json) {
        List<TestCase> out = new ArrayList<>();
        Pattern row = Pattern.compile(
            "\\{[^}]*\"plugin\"\\s*:\\s*\"([^\"]+)\"" +
            "[^}]*\"enabled\"\\s*:\\s*(true|false)" +
            "[^}]*\"sanity_passed\"\\s*:\\s*(true|false)" +
            "[^}]*\"fail_reason\"\\s*:\\s*\"([^\"]*)\"" +
            "[^}]*\"stack_hash\"\\s*:\\s*\"([^\"]*)\"",
            Pattern.DOTALL);
        Matcher m = row.matcher(json);
        while (m.find()) {
            TestCase tc = new TestCase();
            tc.plugin = m.group(1);
            tc.enabled = Boolean.parseBoolean(m.group(2));
            tc.sanityPassed = Boolean.parseBoolean(m.group(3));
            tc.failReason = m.group(4);
            tc.stackHash = m.group(5);
            tc.passed = tc.enabled && tc.sanityPassed;
            out.add(tc);
        }
        return out;
    }

    static void writeJunit(OutputStream os, String variant, List<TestCase> cases) throws IOException {
        StringBuilder sb = new StringBuilder();
        long failures = cases.stream().filter(c -> !c.passed).count();
        sb.append("<?xml version=\"1.0\" encoding=\"UTF-8\"?>\n");
        sb.append("<testsuite name=\"nms-compat-").append(esc(variant))
          .append("\" tests=\"").append(cases.size())
          .append("\" failures=\"").append(failures).append("\">\n");
        for (TestCase tc : cases) {
            sb.append("  <testcase classname=\"NmsCompat.").append(esc(variant))
              .append("\" name=\"").append(esc(tc.plugin)).append("\">\n");
            if (!tc.passed) {
                sb.append("    <failure message=\"")
                  .append(esc(tc.failReason.isEmpty() ? "sanity_failed" : tc.failReason))
                  .append("\">stack_hash=").append(esc(tc.stackHash)).append("</failure>\n");
            }
            sb.append("  </testcase>\n");
        }
        sb.append("</testsuite>\n");
        os.write(sb.toString().getBytes(StandardCharsets.UTF_8));
    }

    static String esc(String s) {
        return s.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;")
                .replace("\"", "&quot;").replace("'", "&apos;");
    }

    static final class TestCase {
        String plugin;
        boolean enabled;
        boolean sanityPassed;
        String failReason;
        String stackHash;
        boolean passed;
    }
}
