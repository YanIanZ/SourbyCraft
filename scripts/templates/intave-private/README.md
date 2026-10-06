# Private Intave preparation workspace

This is a relocated upstream baseline, not a native anticheat build. Native lifecycle, packet
adapter and owner-tick integration are pending. There is no active protection and no server or
plugin JAR task. Keep the upstream license alongside the private source.

Use the SourbyCraft root wrapper with `-p PATH_TO_THIS_WORKSPACE`. The root server's settings,
private toolchain pins and default JAR are not part of this standalone preparation project.

Resolve the exact dependencies and write verification metadata:

```bash
python3 scripts/private_intave_workspace.py resolve
```

This uses a separate writable Gradle home under `.private-intave/gradle-cache`. Once dependencies
and the Gradle distribution are cached, use `--offline` for further baseline builds. Review the
generated `gradle/verification-metadata.xml`; locally recorded checksums establish a subsequent
integrity baseline, not independent authentication of the first download. Snapshot APIs and
legacy local compile JARs require matching provenance as well.

From the SourbyCraft root, run preflight (exit 2 means preparation is incomplete):

```bash
python3 scripts/private_intave_workspace.py doctor --gradle-cache .private-intave/gradle-cache
```

Then run the wrapper with this project directory and the same Gradle home: `compileJava`, followed
by `test`. `test` checks missing binary fixtures/native resources against their pinned Git hashes
and rejects zero discovered tests. Filling `missing-fixtures.txt` paths with matching bytes is
required before running the full upstream suite. Do not replace fixtures with empty placeholders.

`candidate-config.toml` is a proposed config with no runtime consumer. `verification-matrix.json`
tracks pending integration/effectiveness gates. Preparing or compiling these sources does not
complete any native runtime gate.

When terminal network access is available, recover the missing binary resources:

```bash
python3 scripts/private_intave_workspace.py fetch-fixtures
```

Downloads use the full pinned commit, HTTPS, a read timeout and size limit; the exact Git blob hash
must match before a file is created. Existing mismatched files are preserved and reported.
