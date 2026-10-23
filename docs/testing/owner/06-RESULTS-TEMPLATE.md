# Test Result Sheet

Copy this sheet per test case. Fill every field; a blank field is an unverified claim.

---

## Result sheet

- **Test ID:** <!-- e.g. AWF-001 -->
- **Feature:**
- **Date:** <!-- YYYY-MM-DD HH:MM TZ -->
- **SourbyCraft build:** <!-- /ver: Build N + codename -->
- **Git commit:** <!-- full hash; "unknown" is not acceptable -->
- **Jar SHA-256:**
- **Java version:** <!-- java -version, full line -->
- **Operating system:**
- **Hardware:** <!-- CPU model, physical cores, RAM, disk type -->
- **JVM flags:** <!-- exact -Xms/-Xmx/GC/JFR flags -->
- **Installed plugins:** <!-- name + version, and whether bridged -->
- **Configuration:** <!-- file paths + the keys changed; attach the files -->
- **Procedure:** <!-- steps actually run; note any deviation from the document -->
- **Expected result:**
- **Actual result:**
- **Status:** <!-- PASSED | FAILED | BLOCKED | NEEDS RETEST -->
- **Log excerpt:** <!-- the relevant lines, including the marker lines -->
- **Screenshot / video:** <!-- paths or links -->
- **Spark / JFR result:** <!-- link or path; state whether it was a certified run -->
- **Bug ID:** <!-- if FAILED, the id from 07-BUG-REPORT-TEMPLATE -->
- **Retest required:** <!-- yes/no and why -->

---

## Session summary table

One row per test run in a session. Keep this table at the top of the session folder so the next
session can see what changed.

| Test ID | Build | Commit | Date | Status | Bug ID | Evidence path | Notes |
| --- | --- | --- | --- | --- | --- | --- | --- |
|  |  |  |  |  |  |  |  |
|  |  |  |  |  |  |  |  |
|  |  |  |  |  |  |  |  |

---

## Failure summary table

| Bug ID | Test ID | Severity | Symptom (one line) | Reproduced? | Owner decision |
| --- | --- | --- | --- | --- | --- |
|  |  |  |  |  |  |

---

## Session notes

- Environment anomalies (other processes, thermal throttling, network):
- Configuration restored after the session? (yes/no, and how):
- Servers/Redis stopped and disposable directories removed? (yes/no):
- Anything that should change in the procedure itself:
