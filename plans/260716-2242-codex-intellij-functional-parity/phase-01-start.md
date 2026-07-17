---
phase: 1
title: "Bootstrap and Capability Contract"
status: completed
priority: P1
dependencies: []
---

# Phase 1: Bootstrap and Capability Contract

## Overview

Bootstrap a reproducible one-module plugin, establish executable trust, and freeze the complete 0.144.5 stable/experimental protocol evidence. Runnable result: a clean checkout opens a native Tool Window showing reviewed binary/schema compatibility.

## Context Links

- [Plan/red-team decisions](./plan.md); [app-server research](../reports/260716-2240-app-server-capability-research.md); [platform research](../reports/260716-2240-intellij-platform-research.md); [VS-00](../reports/260716-2240-parity-test-research.md)
- Gate: no Phase 2 work until clean-checkout wrapper, trust-policy, full-tree schema, and method-map tests pass.

## Requirements

- Build: Java 21, Kotlin 2.3.20, Platform plugin 2.18.1, IDEA `2026.1.4` build `261.26222.65`, strict `261.*`; Gradle 9.0.0 wrapper scripts/JAR/properties committed with official distribution SHA-256 `8fad3d78296ca518113f3d29016617c7f9367dc005f932bd9d93bf45ba46072b` and wrapper-JAR SHA-256 `76805e32c009c0cf0dd5d206bddc9fb22ea42e84db904b764f3047de095493f3` (Platform plugin 2.18.1 requires Gradle ≥ 9.0.0; supersedes earlier 8.13 pin).
- Trust: `CodexBinaryTrustPolicy` accepts only a canonical regular executable; records local canonical path plus SHA-256, size, file identity, and `codex --version`; revalidates before schema generation and every launch; changed identity/hash/version needs explicit confirmation.
- Environment: child starts from explicit allowlist `HOME`, `PATH`, `CODEX_HOME`, `XDG_CONFIG_HOME`, `XDG_CACHE_HOME`, `XDG_DATA_HOME`, `LANG`, `LC_*`, `TMPDIR`; any extra key is per-key opt-in/previewed, values never logged; no token/proxy env inherited silently.
- Protocol: handwritten minimal envelope/known DTOs plus `JsonObject` unknown boundary. Commit full per-type stable and experimental schema trees, exact method → request/response/notification schema map, inclusion list, and root/file hashes; CI validates mappings and DTO fixtures. No Kotlin code generation claim.
- Provenance: manifest records binary basename/version/hash/size, schema command/flags, full-tree roots/hashes, reviewer/date/decision. It is reviewed evidence, not trust self-attestation; runtime independently revalidates the binary.

## Architecture

`CodexBinaryTrustPolicy` gates both explicit Gradle schema tasks and Phase 2 process launch. `ProtocolContractValidator` loads `method-schema-map.json`, resolves every mapped per-type schema from the checked-in full trees, and validates handwritten DTO golden JSON. `CodexProjectService(Project, CoroutineScope)` exposes only a `CompatibilitySnapshot`; raw schemas/JSON remain in `appserver`.

## Related Code Files

| Action | Exact absolute path | Rough size | Test impact |
|---|---|---:|---|
| Create | `/home/haibachvan/Workspace/CodexIntelliJ/gradlew` | generated | Linux clean-checkout entry |
| Create | `/home/haibachvan/Workspace/CodexIntelliJ/gradlew.bat` | generated | complete wrapper artifact |
| Create | `/home/haibachvan/Workspace/CodexIntelliJ/gradle/wrapper/gradle-wrapper.jar` | binary | official JAR hash gate |
| Create | `/home/haibachvan/Workspace/CodexIntelliJ/gradle/wrapper/gradle-wrapper.properties` | 10 LOC | Gradle 8.13 URL + distribution SHA |
| Create | `/home/haibachvan/Workspace/CodexIntelliJ/settings.gradle.kts` | 20 LOC | module discovery |
| Create | `/home/haibachvan/Workspace/CodexIntelliJ/build.gradle.kts` | 150 LOC | build/schema/contract gates |
| Create | `/home/haibachvan/Workspace/CodexIntelliJ/gradle.properties` | 15 LOC | deterministic build |
| Create | `/home/haibachvan/Workspace/CodexIntelliJ/gradle/verification-metadata.xml` | generated/reviewed | dependency checksums |
| Create | `/home/haibachvan/Workspace/CodexIntelliJ/src/main/resources/META-INF/plugin.xml` | 45 LOC | descriptor checks |
| Create | `/home/haibachvan/Workspace/CodexIntelliJ/src/main/resources/messages/CodexBundle.properties` | 20 LOC | UI labels |
| Create | `/home/haibachvan/Workspace/CodexIntelliJ/src/main/kotlin/dev/haibachvan/codexintellij/CodexProjectService.kt` | 45 LOC | lifecycle seam |
| Create | `/home/haibachvan/Workspace/CodexIntelliJ/src/main/kotlin/dev/haibachvan/codexintellij/appserver/CodexBinaryTrustPolicy.kt` | 170 LOC | path/hash/env revalidation |
| Create | `/home/haibachvan/Workspace/CodexIntelliJ/src/main/kotlin/dev/haibachvan/codexintellij/appserver/ProtocolContractValidator.kt` | 170 LOC | exact schema mapping |
| Create | `/home/haibachvan/Workspace/CodexIntelliJ/src/main/kotlin/dev/haibachvan/codexintellij/ui/CodexToolWindowFactory.kt` | 50 LOC | platform test |
| Create | `/home/haibachvan/Workspace/CodexIntelliJ/src/main/kotlin/dev/haibachvan/codexintellij/ui/CompatibilityPanel.kt` | 80 LOC | compatibility/trust UI |
| Create tree | `/home/haibachvan/Workspace/CodexIntelliJ/protocol-schema/codex-0.144.5/stable/` | full CLI output | every stable per-type schema |
| Create tree | `/home/haibachvan/Workspace/CodexIntelliJ/protocol-schema/codex-0.144.5/experimental/` | full CLI output | every X per-type schema |
| Create | `/home/haibachvan/Workspace/CodexIntelliJ/protocol-schema/codex-0.144.5/schema-inventory.txt` | generated/reviewed | exact relative inclusion set |
| Create | `/home/haibachvan/Workspace/CodexIntelliJ/protocol-schema/codex-0.144.5/schema-manifest.json` | generated/reviewed | file/root hashes + provenance |
| Create | `/home/haibachvan/Workspace/CodexIntelliJ/protocol-schema/codex-0.144.5/method-schema-map.json` | reviewed | exact request/response/notification map |
| Create | `/home/haibachvan/Workspace/CodexIntelliJ/src/test/kotlin/dev/haibachvan/codexintellij/BuildContractTest.kt` | 160 LOC | wrapper/clean build/tree hashes |
| Create | `/home/haibachvan/Workspace/CodexIntelliJ/src/test/kotlin/dev/haibachvan/codexintellij/appserver/CodexBinaryTrustPolicyTest.kt` | 180 LOC | trust/env policy |
| Create | `/home/haibachvan/Workspace/CodexIntelliJ/src/test/kotlin/dev/haibachvan/codexintellij/appserver/ProtocolContractValidatorTest.kt` | 200 LOC | schema/DTO map coverage |

## Functions and Interfaces Checklist

- [x] `CodexBinaryTrustPolicy.inspect(path): BinaryIdentity`; `confirm(identity)` stores local reviewed baseline; `revalidate(expected): TrustDecision`; `environment(extraKeys): PreviewedEnvironment`.
- [x] `BinaryIdentity(canonicalPath, sha256, size, fileKey, versionText)` is re-read immediately before exec; symlink, directory, non-executable, TOCTOU change, or mismatched version rejects.
- [x] `ProtocolContractValidator.validateTrees(inventory, manifest)` proves all included files and root hashes; `validateMethodMap()` proves exact mapped schemas exist/classify stable/X; `validateGolden(method,json)` validates handwritten DTO JSON.
- [x] Gradle tasks `generateCodexStableSchema`, `generateCodexExperimentalSchema`, `verifyCodexSchemaManifest`, `verifyProtocolContract`, and `cleanCheckoutGate`; generation requires a confirmed binary but the manifest cannot confer runtime trust.
- [x] `CompatibilitySnapshot` shows expected/detected build, binary hash prefix/version/review state, schema roots/hashes; contains no secret/path value.

## Implementation Steps

1. Generate Gradle 9.0.0 wrapper; set `distributionSha256Sum`; verify wrapper JAR against official checksum; configure exact toolchain/platform pins, dependency verification, no bundled Kotlin stdlib/coroutines.
2. Implement trust inspection/revalidation and confirmation UI. Use canonical `NOFOLLOW_LINKS` regular/executable checks, SHA/version/file identity, exec-time recheck, explicit environment allowlist and extra-key preview.
3. Generate both complete schema trees with the confirmed 0.144.5 binary. Normalize only nondeterministic path metadata; preserve every per-type schema. Produce sorted inventory, per-file/root hashes, provenance/review record, and exact method-schema map.
4. Define minimal handwritten JSON-RPC/known DTO contracts and `JsonObject` unknown boundary; validate golden request/response/notification JSON against mapped stable/X schemas. Reject absent/ambiguous/unmapped methods.
5. Add status-only Tool Window and tests; run clean-checkout gate in a temporary copied checkout using only `./gradlew` and committed artifacts.

## Todo

- [x] Commit and checksum all four wrapper artifacts; pass clean checkout.
- [x] Implement binary/env trust policy and change confirmation.
- [x] Freeze full schema trees, reviewed provenance, inventory/hashes, exact method map.
- [x] Validate handwritten wire contracts and render compatibility status.

## Test Scenario Matrix

| Priority | Scenario | Expected |
|---|---|---|
| Critical | Clean checkout, no system Gradle | committed `./gradlew` runs all Phase 1 gates |
| Critical | Binary symlink/hash/version/file identity changes between review and exec | launch/generation stops; new explicit confirmation required |
| Critical | Method map or per-type schema omitted/tampered | inventory/root/hash/schema validation fails CI |
| High | Extra/token/proxy environment present | not inherited without per-key preview/opt-in; values absent from logs |
| High | Handwritten DTO golden stable/X mismatch | exact mapped schema test fails; unknown stays `JsonObject` |
| Medium | Source paths enter manifest/UI | sanitization/privacy test fails; local trust store retains path separately |

## Success Criteria

- [x] `./gradlew cleanCheckoutGate verifyCodexSchemaManifest verifyProtocolContract test --tests '*BuildContractTest' --tests '*CodexBinaryTrustPolicyTest' --tests '*ProtocolContractValidatorTest'`
- [x] `./gradlew verifyPluginProjectConfiguration verifyPluginStructure buildPlugin`
- [x] `./gradlew runIde` opens compatibility status and requires confirmation when binary identity changes.

## Risk Assessment / Security

A committed manifest can be forged with a substituted binary, so trust derives from independent exec-time identity revalidation plus human-reviewed baseline. Wrapper and dependencies are supply-chain inputs: pin URL/checksums and verification metadata. Avoid shell interpolation, implicit environment inheritance, raw canonical paths in repo/diagnostics, auth-file reads, sockets, or first-party identity impersonation.

## Dependency Map / Next Steps

No prerequisites. Phase 2 consumes the confirmed binary, exact environment preview, handwritten wire boundary, full trees, and schema map; every Phase 1 gate must pass first.
