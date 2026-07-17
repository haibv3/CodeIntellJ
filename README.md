# Codex IntelliJ

Native IntelliJ IDEA Community plugin for Codex IDE workflows over the public `codex app-server` stdio contract (0.144.5).

## Requirements

- Java 21
- Gradle wrapper (9.0.0; required by IntelliJ Platform Gradle Plugin 2.18.1)
- IntelliJ IDEA `2026.1.4` (`261.*`)
- Local `codex` CLI `0.144.5` for live app-server use

## Build / test

```bash
export JAVA_HOME=$(dirname $(dirname $(readlink -f $(which java)))) # or Java 21 home
./gradlew test
./gradlew verifyCodexSchemaManifest verifyProtocolContract
./gradlew verifyPluginProjectConfiguration verifyPluginStructure buildPlugin verifyPlugin
```

## Run IDE sandbox

```bash
./gradlew runIde
```

1. Open the **Codex** tool window.
2. Confirm the Codex binary (canonical regular file; symlinks rejected).
3. Connect / restart to negotiate capabilities.
4. Use Chat A / Chat B panels for multi-panel sessions.

## Scope holds

`/cloud`, `/cloud-environment`, and `/worktree` are visible-disabled until a public contract exists.
`/side` stays disabled until an accepted semantic trace exists.
`/plan` and `/memories` require experimental API opt-in.
Project/context targeting is limited to canonical IntelliJ content roots.

## Docs

- `docs/system-architecture.md`
- `docs/capability-parity.md`
- `docs/privacy-and-security.md`
- `docs/troubleshooting.md`
- `docs/internal-release-checklist.md`
