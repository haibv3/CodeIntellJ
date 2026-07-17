#!/usr/bin/env python3
import json, sys

def main():
    if len(sys.argv) >= 2 and sys.argv[1] == "--version":
        print("codex-cli 0.144.5")
        return
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
            sys.stdout.write(json.dumps(resp) + "\n")
            sys.stdout.flush()
        elif method == "initialized":
            continue
        else:
            # echo unknown as error response when id present
            if "id" in msg:
                resp = {
                    "jsonrpc": "2.0",
                    "id": msg["id"],
                    "error": {"code": -32601, "message": f"Method not found: {method}"},
                }
                sys.stdout.write(json.dumps(resp) + "\n")
                sys.stdout.flush()

if __name__ == "__main__":
    main()
