# Troubleshooting

## Binary confirmation required

Select the canonical regular `codex` executable (not a symlink). Identity/hash/version changes require reconfirmation.

## Initialize / handshake failures

Confirm `codex --version` is `0.144.5` and `codex app-server` speaks JSONL on stdio.

## Experimental commands unavailable

Enable experimental API opt-in in Settings → Tools → Codex. `/plan` and `/memories` remain gated.

## Worktree / cloud disabled

These commands stay visible-disabled until a public app-server contract exists.
