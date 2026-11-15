#!/usr/bin/env bash
# Starts the packaged MCP server in an empty folder and checks it answers the handshake and lists its tools.
# Usage: scripts/smoke-release.sh <unpacked release folder> <expected version>
set -euo pipefail
PKG="${1:?Usage: smoke-release.sh <unpacked release folder> <expected version>}"
VERSION="${2:?expected version}"
WORK="$(mktemp -d)"
trap 'rm -rf "$WORK"' EXIT
# A tests repo to serve. Starting with no repo at all is not supported yet (see the release plan).
cp -R "$PKG/skeleton/." "$WORK/"
cd "$WORK"
OUT="$(printf '%s\n%s\n%s\n' \
  '{"jsonrpc":"2.0","id":1,"method":"initialize","params":{"protocolVersion":"2025-06-18","capabilities":{},"clientInfo":{"name":"smoke","version":"1"}}}' \
  '{"jsonrpc":"2.0","method":"notifications/initialized"}' \
  '{"jsonrpc":"2.0","id":2,"method":"tools/list"}' \
  | "$PKG/bin/quartet-qe" mcp)"
echo "$OUT" | grep -q '"name":"quartet-qe"' || { echo "No handshake answer:"; echo "$OUT"; exit 1; }
echo "$OUT" | grep -q "\"version\":\"$VERSION\"" || { echo "Expected version $VERSION in the handshake:"; echo "$OUT" | head -c 600; exit 1; }
echo "$OUT" | grep -q 'quartet_start' || { echo "The tool list is missing quartet_start."; exit 1; }
test -f "$PKG/skeleton/quartet-qe" || { echo "skeleton is missing from the package."; exit 1; }
test -f "$PKG/scripts/new-tests-repo.sh" || { echo "new-tests-repo.sh is missing from the package."; exit 1; }
echo "OK: $VERSION answers the MCP handshake and lists its tools."
