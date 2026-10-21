#!/usr/bin/env python3
"""Call a Thumbs MCP tool from the command line (development helper, stdlib only).

    export THUMBS_URL=http://100.x.y.z:8765/mcp   # or http://127.0.0.1:8765/mcp with `adb forward tcp:8765 tcp:8765`
    export THUMBS_TOKEN=thumbs_...
    scripts/mcp_call.py list
    scripts/mcp_call.py get_ui_tree
    scripts/mcp_call.py tap '{"text": "Settings"}'
    scripts/mcp_call.py screenshot --image /tmp/screen.jpg
"""
import base64
import json
import os
import sys
import urllib.error
import urllib.request


def rpc(method, params=None):
    body = {"jsonrpc": "2.0", "id": 1, "method": method}
    if params is not None:
        body["params"] = params
    req = urllib.request.Request(
        os.environ.get("THUMBS_URL", "http://127.0.0.1:8765/mcp"),
        data=json.dumps(body).encode(),
        headers={
            "Authorization": "Bearer " + os.environ["THUMBS_TOKEN"],
            "Content-Type": "application/json",
            "Accept": "application/json, text/event-stream",
        },
    )
    try:
        with urllib.request.urlopen(req, timeout=120) as resp:
            return json.loads(resp.read())
    except urllib.error.HTTPError as e:
        sys.exit(f"HTTP {e.code}: {e.read().decode()}")


def main():
    args = sys.argv[1:]
    if not args:
        sys.exit(__doc__)
    image_path = None
    if "--image" in args:
        i = args.index("--image")
        image_path = args[i + 1]
        del args[i : i + 2]
    if args[0] == "list":
        for t in rpc("tools/list")["result"]["tools"]:
            print(f"{t['name']}: {t['description'][:100]}")
        return
    arguments = json.loads(args[1]) if len(args) > 1 else {}
    resp = rpc("tools/call", {"name": args[0], "arguments": arguments})
    if "error" in resp:
        sys.exit(f"RPC error: {resp['error']}")
    result = resp["result"]
    images = 0
    for c in result["content"]:
        if c["type"] == "text":
            print(c["text"])
        elif c["type"] == "image":
            path = image_path or f"/tmp/thumbs-{images}.{'png' if c['mimeType'].endswith('png') else 'jpg'}"
            if images and image_path:
                path = f"{image_path}.{images}"
            with open(path, "wb") as f:
                f.write(base64.b64decode(c["data"]))
            print(f"[image {c['mimeType']} saved to {path}]")
            images += 1
    if result.get("isError"):
        sys.exit(1)


if __name__ == "__main__":
    main()
