#!/usr/bin/env bash
set -euo pipefail
ROOT="$(cd "$(dirname "$0")/../../.." && pwd)"
exec python3 "$ROOT/src/test/resources/fixtures/appserver/fake-codex-app-server.py" "$@"
