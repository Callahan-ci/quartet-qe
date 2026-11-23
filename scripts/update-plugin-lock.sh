#!/usr/bin/env bash
# After a release is published, pins the Claude plugin to it: writes the version and the SHA-256 of each platform's file
# into plugin/scripts/start-quartet.sh, and sets the version in plugin/.claude-plugin/plugin.json.
# Usage: scripts/update-plugin-lock.sh 0.2.0 [SHA256SUMS file or URL]   (default: that release's SHA256SUMS on GitHub)
# Commit the result to the branch the directory follows. The plugin only fetches what these checksums name.
set -euo pipefail
cd "$(dirname "$0")/.."
VERSION="${1:?Usage: update-plugin-lock.sh <version, such as 0.2.0> [SHA256SUMS file or URL]}"
case "$VERSION" in [0-9]*.[0-9]*.[0-9]*) ;; *) echo "The version looks like 0.2.0, not $VERSION" >&2; exit 1 ;; esac
SOURCE="${2:-https://github.com/Callahan-ci/quartet-qe/releases/download/v$VERSION/SHA256SUMS}"
SUMS="$(mktemp)"; trap 'rm -f "$SUMS"' EXIT
if [ -f "$SOURCE" ]; then cp "$SOURCE" "$SUMS"; else curl -fsSL "$SOURCE" -o "$SUMS"; fi

LAUNCHER=plugin/scripts/start-quartet.sh
for platform in mac-arm64 mac linux linux-arm64; do
  sum="$(awk -v f="quartet-qe-$VERSION-$platform.mcpb" '$2 == f || $2 == "*" f { print $1 }' "$SUMS")"
  case "$sum" in [0-9a-f]*) [ "${#sum}" -eq 64 ] || { echo "No valid checksum for $platform in $SOURCE" >&2; exit 1; } ;; *) echo "No checksum for $platform in $SOURCE" >&2; exit 1 ;; esac
  var="SHA256_${platform//-/_}"
  VAR="$var" SUM="$sum" perl -pi -e 's/^\Q$ENV{VAR}\E="[^"]*"/$ENV{VAR}="$ENV{SUM}"/' "$LAUNCHER"
done
VERSION="$VERSION" perl -pi -e 's/^VERSION="[^"]*"/VERSION="$ENV{VERSION}"/' "$LAUNCHER"
VERSION="$VERSION" perl -pi -e 's/^(\s*"version":\s*)"[^"]*"/$1"$ENV{VERSION}"/' plugin/.claude-plugin/plugin.json
if grep -q '^SHA256_[a-z_0-9]*="UNRELEASED"' "$LAUNCHER"; then echo "Some platform was left unpinned." >&2; exit 1; fi
git --no-pager diff --stat -- plugin | tail -3
echo "Pinned the plugin to $VERSION. Review the diff, then commit it."
