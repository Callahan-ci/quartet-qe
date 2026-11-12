#!/usr/bin/env bash
# Creates a tests repo from the skeleton. Usage: ./new-tests-repo.sh ../my-product-tests
set -euo pipefail
HERE="$(cd "$(dirname "$0")" && pwd)"
TARGET="${1:?Usage: ./new-tests-repo.sh <new-folder>}"
[ -e "$TARGET" ] && { echo "$TARGET already exists."; exit 1; }
URL="$(git -C "$HERE" remote get-url origin)"
NAME="$(basename "$TARGET")"

mkdir -p "$TARGET/context"
cp -R "$HERE/skeleton/." "$TARGET/"
cp -R "$HERE/gradle" "$HERE/gradlew" "$HERE/gradlew.bat" "$HERE/connectors.yaml" "$TARGET/"
cp -R "$HERE/context/engineering-principles" "$HERE/context/playbooks" "$HERE/context/README.md" "$TARGET/context/"
perl -pi -e "s|__NAME__|$NAME|g; s|__QUARTET_URL__|${URL%.git}|g" "$TARGET/README.md" "$TARGET/settings.gradle.kts"
cp "$HERE/get-started.sh" "$TARGET/"
chmod +x "$TARGET/quartet-qe" "$TARGET/gradlew" "$TARGET/get-started.sh"

cd "$TARGET"
git init -q -b main
git submodule add -q "$URL" .quartet
git add -A
echo "Created $NAME. Next: cd into it and run ./get-started.sh"
