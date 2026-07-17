# Privacy and Security

- Runtime trust revalidates the Codex executable independently of the schema manifest.
- Child process environment is allowlisted; token/proxy keys cannot be opted in.
- Diagnostics are redacted before ring-buffer storage; feedback uploads require explicit consent and use redacted bundles only.
- MCP tool calls require immutable preview hash consent.
- Context/path targeting is restricted to canonical IntelliJ content roots.
- Compatibility UI shows hash prefixes and versions, never absolute secret-bearing paths in exports.
