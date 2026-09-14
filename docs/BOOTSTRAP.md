# Bootstrap continuation — 2026-09-14

SourbyClip 3.0.22 contains the downloader transfer loop from Claude's 90d5b03
and the launcher exit-code repair from Codex. The server now resolves that
version from the checked-in Maven mirror. Existing 3.0.21 artifacts remain intact.

Previously, a bootstrap exception printed a stack trace and returned exit code 0.
The launcher now exits 1 while retaining the diagnostic cause. Four process tests
exercise argument forwarding and success, a bootstrap exception, a wrapped Error,
and a missing bootstrap class. The production launcher still targets Java 6 using
the JDK 11 toolchain; source-level process tests run on JDK 25 with release 8.

Testing the assembled jar exposed another boundary: ServerMain could fail after
the reflective invocation returned, also yielding exit 0. Its catch now prints the
cause and exits 1. ClipLaunchProbe.java exercises the actual packaged thread factory
in separate success/failure processes; CI checks the failure code and diagnostic.

The packaged bootstrap probe exercises a payload larger than the 8 MiB transfer
chunk, corrupt-cache replacement, hash rejection, and verified-cache reuse after
removing the source. It uses local files and makes no remote requests:

```sh
java --class-path sourby-maven/dev/iyanz/sourbyclip/3.0.22/sourbyclip-3.0.22.jar \
  scripts/fixtures/ClipDownloadProbe.java build/clip-download-probe
```

These checks do not qualify remote download timeouts, retry policy, concurrent
downloads, or a clean-cache full server boot. Those tasks remain open in section L
of DEVELOPMENT-TASKS.md. A hash-mismatched file remains rejected; validation is not
bypassed to recover a failed bootstrap.

## Branch CI

Claude separately audited CI identity and publication boundaries. Build/boot/Docker
checks now include branch 26.2. The three Gradle channel calculations agree:
release/* → REL, feat/* and experimental/* → EXP, otherwise DEV. Detached PR builds
use DEV. CI selects corresponding artifact filenames. Normal checks can reuse the
configured build number; publication still requires an unused number and passing
build/boot/Docker jobs on release/26.2-canvas.

This enables development verification; it does not publish stable build 46 or claim
benchmark/soak qualification. The legacy sourbypatcher CI step remains pending its
separate clean-build dependency audit.
