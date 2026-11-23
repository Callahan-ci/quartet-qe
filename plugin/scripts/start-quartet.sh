#!/bin/sh
# Starts Quartet QE for Claude. Quartet QE is a Java program, so this script finds it, or fetches it once.
#
# What it does, in order:
#   1. If QUARTET_HOME points at an install you already have, it uses that.
#   2. Otherwise it uses ~/.quartet/releases/<version>, if that exists.
#   3. Otherwise it downloads this version's release from GitHub Releases, checks the file's SHA-256 against the value
#      written below (a mismatch stops here: nothing is unpacked or run), and unpacks it into ~/.quartet/releases/<version>.
# Then it starts the program. Everything it says goes to stderr, because stdout belongs to the MCP protocol.
# It runs only what that release contains. It sends nothing anywhere, and it fetches only the one release file.
set -eu

VERSION="0.1.0"
# quartet:lock begin (rewritten by scripts/update-plugin-lock.sh in the Quartet QE repository when a release is published)
SHA256_mac_arm64="UNRELEASED"
SHA256_mac="UNRELEASED"
SHA256_linux="UNRELEASED"
SHA256_linux_arm64="UNRELEASED"
# quartet:lock end

say() { printf 'Quartet QE: %s\n' "$*" >&2; }
fail() { say "$*"; exit 1; }

# 1. An install you already have.
if [ -n "${QUARTET_HOME:-}" ] && [ -x "$QUARTET_HOME/bin/quartet-qe" ]; then
  exec "$QUARTET_HOME/bin/quartet-qe" mcp
fi

# Java 21 or newer is needed to run it.
command -v java >/dev/null 2>&1 || fail "Java 21 or newer is needed and was not found. Install a JDK (for example from https://adoptium.net), then restart."
JAVA_MAJOR="$(java -version 2>&1 | sed -n '1s/.*version "\([0-9][0-9]*\).*/\1/p')"
[ "${JAVA_MAJOR:-0}" -ge 21 ] 2>/dev/null || fail "Java 21 or newer is needed. Found version ${JAVA_MAJOR:-unknown}. Install a newer JDK, then restart."

# 2. A release already unpacked.
ROOT="$HOME/.quartet/releases/$VERSION"
if [ -x "$ROOT/bin/quartet-qe" ]; then
  exec "$ROOT/bin/quartet-qe" mcp
fi

# 3. Fetch it, once.
case "$(uname -s)-$(uname -m)" in
  Darwin-arm64)            PLATFORM="mac-arm64";   WANT="$SHA256_mac_arm64" ;;
  Darwin-x86_64)           PLATFORM="mac";         WANT="$SHA256_mac" ;;
  Linux-x86_64)            PLATFORM="linux";       WANT="$SHA256_linux" ;;
  Linux-aarch64|Linux-arm64) PLATFORM="linux-arm64"; WANT="$SHA256_linux_arm64" ;;
  *) fail "This computer ($(uname -s) $(uname -m)) is not supported. Quartet QE runs on macOS and Linux." ;;
esac
if [ "$WANT" = "UNRELEASED" ] && [ "${QUARTET_ALLOW_UNPINNED:-}" != "1" ]; then
  fail "This plugin version has no published release to fetch yet, so it will not download anything. Install Quartet QE yourself and set QUARTET_HOME to it."
fi
command -v curl >/dev/null 2>&1 || fail "curl is needed to fetch Quartet QE and was not found."
command -v unzip >/dev/null 2>&1 || fail "unzip is needed to unpack Quartet QE and was not found."

BASE="${QUARTET_RELEASE_BASE_URL:-https://github.com/Callahan-ci/quartet-qe/releases/download/v$VERSION}"
FILE="quartet-qe-$VERSION-$PLATFORM.mcpb"
mkdir -p "$HOME/.quartet/releases"
WORK="$(mktemp -d "$HOME/.quartet/.fetch.XXXXXX")"
trap 'rm -rf "$WORK"' EXIT INT TERM

say "Fetching Quartet QE $VERSION for $PLATFORM (first use only)..."
curl -fsSL --retry 3 -o "$WORK/$FILE" "$BASE/$FILE" || fail "Could not download $BASE/$FILE"

if command -v sha256sum >/dev/null 2>&1; then GOT="$(sha256sum "$WORK/$FILE" | cut -d' ' -f1)"; else GOT="$(shasum -a 256 "$WORK/$FILE" | cut -d' ' -f1)"; fi
if [ "$WANT" != "UNRELEASED" ] && [ "$GOT" != "$WANT" ]; then
  fail "The downloaded file does not match the checksum this plugin expects, so it was not unpacked. Expected $WANT, got $GOT."
fi

unzip -q "$WORK/$FILE" -d "$WORK/unpacked" || fail "Could not unpack $FILE"
[ -f "$WORK/unpacked/bin/quartet-qe" ] || fail "The download does not look like Quartet QE (no bin/quartet-qe)."
chmod +x "$WORK/unpacked/bin/quartet-qe"
if [ ! -x "$ROOT/bin/quartet-qe" ]; then
  mv "$WORK/unpacked" "$ROOT" 2>/dev/null || [ -x "$ROOT/bin/quartet-qe" ] || fail "Could not install into $ROOT"
fi
# exec replaces this shell, so its exit trap would never run: remove the download now.
rm -rf "$WORK"
trap - EXIT INT TERM
exec "$ROOT/bin/quartet-qe" mcp
