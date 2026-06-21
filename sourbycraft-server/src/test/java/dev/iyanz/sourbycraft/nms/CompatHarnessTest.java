package dev.iyanz.sourbycraft.nms;

import org.junit.jupiter.api.Test;

import java.io.ByteArrayOutputStream;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class CompatHarnessTest {

    @Test
    void parsesTwoRowsFromJson() {
        String json = "[\n" +
            "  {\"plugin\":\"Citizens\",\"enabled\":true,\"sanity_passed\":true,\"fail_reason\":\"\",\"stack_hash\":\"\"},\n" +
            "  {\"plugin\":\"NBTAPI\",\"enabled\":true,\"sanity_passed\":false,\"fail_reason\":\"NoSuchMethodError\",\"stack_hash\":\"abc123\"}\n" +
            "]\n";
        List<CompatHarness.TestCase> cases = CompatHarness.parseRows("mojmap", json);

        assertEquals(2, cases.size());
        assertEquals("Citizens", cases.get(0).plugin);
        assertTrue(cases.get(0).passed);
        assertEquals("NBTAPI", cases.get(1).plugin);
        assertFalse(cases.get(1).passed);
        assertEquals("NoSuchMethodError", cases.get(1).failReason);
        assertEquals("abc123", cases.get(1).stackHash);
    }

    @Test
    void emitsJunitWithFailureNode() throws Exception {
        String json = "[" +
            "{\"plugin\":\"X\",\"enabled\":true,\"sanity_passed\":false,\"fail_reason\":\"oops\",\"stack_hash\":\"ff\"}" +
            "]";
        List<CompatHarness.TestCase> cases = CompatHarness.parseRows("mojmap", json);
        ByteArrayOutputStream baos = new ByteArrayOutputStream();
        CompatHarness.writeJunit(baos, "mojmap", cases);
        String xml = baos.toString();
        assertTrue(xml.contains("<testsuite name=\"nms-compat-mojmap\""), "header present: " + xml);
        assertTrue(xml.contains("<failure message=\"oops\""), "failure node present: " + xml);
        assertTrue(xml.contains("stack_hash=ff"), "stack hash present: " + xml);
    }

    @Test
    void emptyResultProducesEmptySuite() throws Exception {
        List<CompatHarness.TestCase> cases = CompatHarness.parseRows("mojmap", "[]");
        assertEquals(0, cases.size());
        ByteArrayOutputStream baos = new ByteArrayOutputStream();
        CompatHarness.writeJunit(baos, "mojmap", cases);
        assertTrue(baos.toString().contains("tests=\"0\""));
        assertTrue(baos.toString().contains("failures=\"0\""));
    }
}
