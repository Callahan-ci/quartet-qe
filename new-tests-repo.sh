#!/usr/bin/env bash
# Creates a lean tests repo from the skeleton: tests, context, recordings and settings only.
# Quartet QE itself is installed once per machine, not copied into each repo. Usage: ./new-tests-repo.sh ../my-product-tests
set -euo pipefail
SELF="$(cd "$(dirname "$0")" && pwd)"
# Run from a release download this script sits in scripts/ next to release.properties. From a checkout it sits at the root.
if [ -f "$SELF/../release.properties" ]; then PACKAGED=1; HERE="$(dirname "$SELF")"; else PACKAGED=0; HERE="$SELF"; fi
TARGET="${1:?Usage: ./new-tests-repo.sh <new-folder>}"
[ -e "$TARGET" ] && { echo "$TARGET already exists."; exit 1; }
NAME="$(basename "$TARGET")"
# The name ends up in files and in a perl substitution, so only plain characters are allowed.
case "$NAME" in
  ''|.*|*[!A-Za-z0-9._-]*) echo "The folder name may only contain letters, digits, dots, dashes and underscores, and cannot start with a dot: $NAME" >&2; exit 1 ;;
esac

if [ "$PACKAGED" = 1 ]; then
  # A release: CI fetches this exact release by its tag, and Quartet is already installed from the download.
  URL="$(sed -n 's/^repoUrl=//p' "$HERE/release.properties")"
  REF="v$(sed -n 's/^version=//p' "$HERE/release.properties")"
  mkdir -p "${QUARTET_HOME_LINK:-$HOME/.quartet}"
  ln -sfn "$HERE" "${QUARTET_HOME_LINK:-$HOME/.quartet}/current"
else
  URL="$(git -C "$HERE" remote get-url origin)"
  # The version CI should fetch. Use this checkout's commit if it has been pushed, otherwise the pushed main.
  REF="$(git -C "$HERE" rev-parse HEAD)"
  if [ -z "$(git -C "$HERE" branch -r --contains "$REF" 2>/dev/null)" ]; then
    REF="$(git -C "$HERE" rev-parse origin/main 2>/dev/null || echo main)"
    echo "Note: this Quartet checkout has changes that are not pushed, so CI will use the pushed version ($REF)." >&2
  fi
  "$HERE/install-quartet.sh"
fi

mkdir -p "$TARGET/context"
cp -R "$HERE/skeleton/." "$TARGET/"
# Only the Gradle wrapper, which every Gradle project carries. No scripts, no copied principles or playbooks,
# no Jenkinsfile and no connectors file: Quartet provides those and writes the last two when they are wanted.
mkdir -p "$TARGET/gradle/wrapper"
cp "$HERE/gradle/wrapper/gradle-wrapper.jar" "$HERE/gradle/wrapper/gradle-wrapper.properties" "$TARGET/gradle/wrapper/"
cp "$HERE/gradlew" "$HERE/gradlew.bat" "$TARGET/"
# Values go in through the environment. Interpolating them into the perl text let a crafted folder name run a command.
NAME="$NAME" QURL="${URL%.git}" QREF="$REF" perl -pi -e 's/__NAME__/$ENV{NAME}/g; s/__QUARTET_URL__/$ENV{QURL}/g; s/__QUARTET_REF__/$ENV{QREF}/g' "$TARGET/README.md" "$TARGET/settings.gradle.kts" "$TARGET/quartet.yaml"
chmod +x "$TARGET/quartet-qe" "$TARGET/gradlew"

cd "$TARGET"
./quartet-qe readme >/dev/null 2>&1 || true
git init -q -b main
git add -A
echo "Created $NAME. Next: cd into it and run ./quartet-qe get-started"
