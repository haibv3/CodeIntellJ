#!/usr/bin/env python3
import json
import os
import sys
from pathlib import Path

PERFORMANCE_SCENARIO = "multi-agent-performance"


def write(message):
    sys.stdout.write(json.dumps(message) + "\n")


def notify(method, params):
    write({"jsonrpc": "2.0", "method": method, "params": params})


def performance_spec():
    spec_path = Path(__file__).with_name("multi-agent-performance-spec.json")
    return json.loads(spec_path.read_text(encoding="utf-8"))


def emit_performance_stream():
    spec = performance_spec()
    emitted = 0

    def event(method, params):
        nonlocal emitted
        notify(method, params)
        emitted += 1

    event("thread/started", {"threadId": "perf-thread", "title": "Multi-agent performance"})
    event("turn/started", {"threadId": "perf-thread", "turnId": "perf-turn"})
    event("item/completed", {
        "threadId": "perf-thread",
        "turnId": "perf-turn",
        "itemId": "perf-user",
        "type": "userMessage",
        "text": "Run deterministic multi-agent workload",
    })
    for index in range(spec["agentCount"]):
        event("item/started", {
            "threadId": "perf-thread",
            "turnId": "perf-agents",
            "itemId": f"perf-agent-{index}",
            "type": "subagent",
            "agentId": f"worker-{index}",
            "status": "active",
            "text": f"Worker {index}",
        })
    event("item/completed", {
        "threadId": "perf-thread",
        "turnId": "perf-agents",
        "itemId": "perf-progress",
        "type": "agentMessage",
        "text": "Agents are collecting results",
    })
    result_count = spec["messageCount"] - 1
    for index in range(result_count):
        event("item/completed", {
            "threadId": "perf-thread",
            "turnId": f"perf-result-{index}",
            "itemId": f"perf-result-{index}",
            "type": "agentMessage",
            "text": f"Result {index} revision 0 seed {spec['seed']}",
        })

    update_count = spec["eventCount"] - emitted - 1
    if update_count < 0:
        raise ValueError("eventCount is smaller than the workload core")
    for revision in range(update_count):
        index = revision % result_count
        event("item/updated", {
            "threadId": "perf-thread",
            "turnId": f"perf-result-{index}",
            "itemId": f"perf-result-{index}",
            "type": "agentMessage",
            "text": f"Result {index} revision {revision + 1} seed {spec['seed']}",
        })
    event("turn/completed", {"threadId": "perf-thread", "turnId": "perf-turn"})
    if emitted != spec["eventCount"]:
        raise AssertionError(f"expected {spec['eventCount']} events, emitted {emitted}")
    sys.stdout.flush()

def main():
    if len(sys.argv) >= 2 and sys.argv[1] == "--version":
        print("codex-cli 0.144.5")
        return
    scenario = os.environ.get("CODEX_FIXTURE_SCENARIO")
    # app-server mode: JSONL on stdio
    for raw in sys.stdin:
        line = raw.strip()
        if not line:
            continue
        msg = json.loads(line)
        method = msg.get("method")
        if method == "initialize":
            resp = {
                "jsonrpc": "2.0",
                "id": msg["id"],
                "result": {
                    "codexHome": "/tmp/codex-home-fixture",
                    "platformFamily": "unix",
                    "platformOs": "linux",
                    "userAgent": "codex_app_server/0.144.5",
                },
            }
            write(resp)
            sys.stdout.flush()
        elif method == "initialized":
            continue
        elif scenario == PERFORMANCE_SCENARIO and method == "thread/start":
            write({
                "jsonrpc": "2.0",
                "id": msg["id"],
                "result": {"thread": {"id": "perf-thread"}},
            })
            sys.stdout.flush()
        elif scenario == PERFORMANCE_SCENARIO and method == "turn/start":
            write({
                "jsonrpc": "2.0",
                "id": msg["id"],
                "result": {"turn": {"id": "perf-turn"}},
            })
            sys.stdout.flush()
            emit_performance_stream()
        else:
            # echo unknown as error response when id present
            if "id" in msg:
                resp = {
                    "jsonrpc": "2.0",
                    "id": msg["id"],
                    "error": {"code": -32601, "message": f"Method not found: {method}"},
                }
                write(resp)
                sys.stdout.flush()

if __name__ == "__main__":
    main()
