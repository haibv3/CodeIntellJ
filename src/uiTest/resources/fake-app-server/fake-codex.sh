#!/usr/bin/env bash
set -euo pipefail
ROOT="$(cd "$(dirname "$0")/../../../.." && pwd)"
export CODEX_FIXTURE_SCENARIO="${CODEX_FIXTURE_SCENARIO:-multi-agent-performance}"
exec python3 "$ROOT/src/test/resources/fixtures/appserver/fake-codex-app-server.py" "$@"
