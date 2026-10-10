#!/bin/sh
set -eu

if ! command -v codex >/dev/null 2>&1; then
  echo "codex CLI was not found on PATH" >&2
  exit 1
fi

if [ -z "${MCAI_MCP_TOKEN:-}" ]; then
  echo "Set the same MCAI_MCP_TOKEN in both the Minecraft and Codex environments first." >&2
  exit 1
fi

case "$MCAI_MCP_TOKEN" in
  *[!A-Za-z0-9_-]*)
    echo "MCAI_MCP_TOKEN has an invalid format." >&2
    exit 1
    ;;
esac

codex mcp add minecraft_companion \
  --url http://127.0.0.1:25766/mcp \
  --bearer-token-env-var MCAI_MCP_TOKEN
