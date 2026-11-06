# Security Policy

SourbyCraft is a region-threaded game server with experimental subsystems.
A security report is especially important when it involves:

- authentication, permissions, packet parsing or remote input;
- server crashes, resource exhaustion or command abuse;
- cross-region data races, unsafe bridge behavior or persistent world corruption;
- updater, bootstrap, build or artifact-integrity failures;
- secrets or unintentional exposure of player/server information.

## Responsible reporting

**Do not publish proof-of-concept exploits, private server logs, credentials
or sensitive vulnerability details in a public GitHub issue.**

If GitHub's **Private vulnerability reporting** option is available on the
repository's **Security** tab, use that option. If it is not enabled, open a
public issue **without technical exploit details** asking the maintainer for
a private reporting channel, or use a preexisting private contact method
published on the maintainer's GitHub profile if available.

For the private report, include the affected release/commit, impact,
reproduction steps, a minimal test case, and relevant redacted logs.
Please avoid testing on servers you do not own or have authorization to
assess, and avoid including other people's data.

## Scope and expectations

- The public Java server, its SourbyAPI, release/update path and relevant
  public build scripts are in scope for reporting.
- Private toolchain repositories and third-party plugins are independently
  maintained; describe a suspected interaction, but do not assume those
  projects are part of this security response process.
- An unqualified feature is **not** a supported security guarantee. In
  particular, relaxed Aurora Bridge thread checks can allow unsafe
  cross-region access; see
  [bridge architecture](docs/architecture/aurora-plugin-bridge.md)
  and the [development policy](docs/DEVELOPMENT.md).
- Security and stability claims require owner verification. There is no
  promised response or fix SLA, and this project does not advertise a
  bug-bounty program.

When a fix is ready, the maintainer may coordinate disclosure, a patch
release and user guidance based on the demonstrated impact.
