# Internal Release Checklist

- [ ] `./gradlew cleanCheckoutGate test`
- [ ] `./gradlew verifyCodexSchemaManifest verifyProtocolContract`
- [ ] `./gradlew verifyPluginProjectConfiguration verifyPluginStructure buildPlugin verifyPlugin`
- [ ] Manual `runIde`: confirm binary → connect → chat stream → approval → patch/diff
- [ ] Multi-panel isolation
- [ ] Signed-out / login path
- [ ] MCP consent preview hash equality
- [ ] Disabled `/cloud` `/worktree` `/side` never raw-prompt
- [ ] No secrets in diagnostic bundles
