#!/usr/bin/env bash
# Checks a release package the way a first-time user meets it: nothing set up, an empty folder, a chat tool connecting.
# It starts the MCP server, checks the handshake and tool list, asks to test a site, answers the setup question, and checks
# that a lean tests repo is created and pinned to this release. Everything happens in a throwaway home folder.
# Usage: scripts/smoke-release.sh <unpacked release folder> <expected version>
set -euo pipefail
PKG="$(cd "${1:?Usage: smoke-release.sh <unpacked release folder> <expected version>}" && pwd)"
VERSION="${2:?expected version}"
WORK="$(mktemp -d)"
trap 'rm -rf "$WORK"' EXIT
mkdir -p "$WORK/home" "$WORK/empty"
export HOME="$WORK/home"
export JAVA_OPTS="-Duser.home=$WORK/home"
cd "$WORK/empty"

OUT="$(printf '%s\n' \
  '{"jsonrpc":"2.0","id":1,"method":"initialize","params":{"protocolVersion":"2025-06-18","capabilities":{},"clientInfo":{"name":"smoke","version":"1"}}}' \
  '{"jsonrpc":"2.0","method":"notifications/initialized"}' \
  '{"jsonrpc":"2.0","id":2,"method":"tools/list"}' \
  '{"jsonrpc":"2.0","id":3,"method":"tools/call","params":{"name":"quartet_start","arguments":{"site":"https://example.com","story_text":"# Home\n\n## Acceptance criteria\n\n- AC1: The home page loads."}}}' \
  '{"jsonrpc":"2.0","id":4,"method":"tools/call","params":{"name":"quartet_setup","arguments":{"name":"Smoke Shop","tests_repo":"new"}}}' \
  | "$PKG/bin/quartet-qe" mcp)"

fail() { echo "FAILED: $1" >&2; echo "$OUT" | head -c 1500 >&2; exit 1; }
echo "$OUT" | grep -q '"name":"quartet-qe"' || fail "no handshake answer"
echo "$OUT" | grep -q "\"version\":\"$VERSION\"" || fail "the handshake does not report version $VERSION"
echo "$OUT" | grep -q 'quartet_start' || fail "the tool list is missing quartet_start"
echo "$OUT" | grep -q '"readOnlyHint"' || fail "the tool list carries no annotations"
echo "$OUT" | grep -q 'needs_decisions' || fail "a first start in an empty folder did not ask where the tests should live"

REPO="$WORK/home/quartet-tests/smoke-shop-tests"
[ -f "$REPO/quartet.yaml" ] || fail "the tests repo was not created at $REPO"
grep -q "^quartetVersion: v$VERSION$" "$REPO/quartet.yaml" || fail "the new repo is not pinned to v$VERSION: $(grep quartetVersion "$REPO/quartet.yaml")"
grep -q 'goughliam1813/quartet-qe' "$REPO/quartet.yaml" || fail "the new repo does not name where to fetch Quartet from"
[ -x "$REPO/gradlew" ] && [ -f "$REPO/gradle/wrapper/gradle-wrapper.jar" ] || fail "the new repo has no Gradle wrapper"
[ -f "$REPO/README.md" ] && [ -d "$REPO/.git" ] || fail "the new repo has no README or git repository"
[ "$(cd "$HOME/.quartet/current" && pwd -P)" = "$(cd "$PKG" && pwd -P)" ] || fail "the new repo cannot find this install (~/.quartet/current)"
# What the repo must not carry: a copy of Quartet.
[ ! -d "$REPO/quartet-core" ] && [ ! -f "$REPO/new-tests-repo.sh" ] || fail "the new repo carries a copy of the framework"
echo "OK: $VERSION starts with no repo, asks where the tests live, and creates a lean repo pinned to v$VERSION."
