# Bug Report Template

Use this when a test fails. The goal is that a coding agent can reproduce the failure without asking
follow-up questions. Do not include secrets, tokens, player passwords or full inventories.

---

## Title

`[AWF] /awf unload reports success while the save failed` — short, feature-prefixed, one line.

## Severity

- **S1 — Data loss or corruption, or a crash of the server.**
- **S2 — Wrong behaviour that can lose work, kick a player, or block an operation.**
- **S3 — Visible but recoverable wrong behaviour.**
- **S4 — Cosmetic, log noise, documentation mismatch.**

## Feature involved

AWF / MultiProfile / Redis Fabric / Async pathfinding / Bridge / Governor / Startup cache /
Chunk metrics / Network / AbyssLink / Updater / Other: …

## Environment

- SourbyCraft build and codename (`/ver`):
- Git commit and jar SHA-256:
- Java version and JVM flags:
- OS and hardware:
- Plugins (name + version) and which are bridged:
- Configuration files and the keys that matter (attach, do not paste a whole server directory):
- `aurora-server.yml` `guard-severity` and `paper-global.yml` `threaded-regions` values:

## Reproduction steps

1. Start from a **disposable** copy of the world/server described above.
2. Exact commands (console vs in-game vs terminal), in order, with coordinates and player names:
3. Timing that matters (for example "disconnect within 2 s of `Switched`").
4. How often it reproduces: `always` / `N of M` / `once`.

## Expected behaviour

One paragraph, and the exact message/line you expected if there is one.

## Actual behaviour

What happened instead, including the exact message or exception.

## Logs

Attach `logs/latest.log` around the failure, or paste the excerpt with timestamps. Include:

- the full stack trace, not only the first line;
- every `AWF-*`, `Aurora Bridge`, `AWF MultiProfile`, `AsyncPath`, `Aurora Redis Fabric` line;
- the lines immediately before the failure (the operation that started it).

## Relevant configuration

The smallest set of keys that changes the behaviour, plus the file path. State whether a restart was
done after editing.

## World state

- World name, generator/environment, template (if an instance);
- whether the world was loaded/unloaded at the time;
- player count and whether players were inside the world;
- dirty chunks at the time (saved or not);
- AWF backend (file/redis) and generation if known (`/awf info`).

## Threading information

- The thread named in the exception (`Region`, `Entity`, `Global`, `Netty`, `Storage`, `AsyncPath`);
- region coordinates / region id if shown;
- `/perf region [<world> <x> <z>]` output around the time;
- `/perf incidents` output if available;
- whether the failure happens only with a bridged plugin.

## Possible regression

- Did this work on Build 49 / an earlier build? Which? What changed between them?
- Is there a configuration change in the same window?
- Did it start after a specific commit or plugin update?

## Supporting screenshots / video

Paths or links. For an inventory/state bug, include before and after.

## Related implementation change

Link the release note entry, TODO item, PR/commit, or the test document section (for example
`docs/testing/owner/01-AWF-TESTING.md` §AWF-006). If unknown, say so.

---

## Agent triage block (filled by the coding agent, not the owner)

- Reproduced from source inspection: yes/no, with file:line
- Suspected root cause:
- Fix or mitigation proposed:
- Test cases that must be rerun:
- New invariant/regression test suggested (see `docs/DEVELOPMENT.md`):
- Status after fix: `OWNER TEST PENDING` — never `PASSED` without the owner's result sheet.
