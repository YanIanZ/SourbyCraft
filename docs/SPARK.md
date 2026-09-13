# Spark configuration reporting

SourbyCraft uses the upstream Spark engine through its existing Canvas integration.
`FoliaSparkPlugin.createServerConfigProvider()` delegates to the Sourby-owned
`SourbyServerConfigProvider`. This is an adapter, not a separate profiler fork.

The `sourbycraft/` report group contains `global.toml` from
`sourbycraft_config/sourbycraft_global_config.toml` and `security.yml` from
`sourbycraft-security.yml`. Missing files are omitted. Collection reads files and
builds report JSON; it does not seed defaults, migrate files, or save configuration.

## Secret filtering

The provider inherits Spark's `BASE_HIDDEN_PATHS`, including management-server
secrets and RCON credentials, and retains the existing proxy/database exclusions.
The operator's `spark.serverconfigs.hiddenpaths` exclusions remain supported.

For parsed TOML/YAML, an additional pass removes credential keys recursively,
including objects inside arrays. Key comparison ignores case and separators.
Keys ending in `password`, `passwd`, `secret`, `token`, `apikey`, `accesskey`,
`privatekey`, `webhook`, or `webhookurl`, and exact `credentials`, `authorization`,
or `connectionstring` keys are omitted with their values. This also handles quoted
literal dotted keys in TOML. Harmless settings such as `token-bucket-size` remain.

This is key-based filtering, not inspection of arbitrary string contents. A secret
placed in a message, custom URL, or an unrecognized field name is not automatically
detectable. Use the operator hidden-path setting for those fields. Spark uses dots
for nested paths and `<dot>` for a literal dot in a key; its upstream path filter
does not itself traverse arrays. Recognized credential keys inside arrays are
handled by SourbyCraft's recursive pass.

## Verification and remaining work

`SourbyServerConfigProviderTest`, selected by `SparkConfigTestSuite`, exercises
synthetic management/RCON secrets, nested TOML array tables, YAML lists, operator
exclusions, grouped filenames, absent files, and byte-preserving collection.
Run the complete server test task: existing forced suites can fail discovery when
an incompatible Gradle `--tests` filter excludes their children.

The parser/provider contract is tested locally without uploading any server
configuration. Verification of rendering in an actual Spark web report is still
pending; local serialization tests do not establish viewer behavior.
