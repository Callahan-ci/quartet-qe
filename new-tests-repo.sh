#!/usr/bin/env bash
# Creates a lean tests repo from the skeleton: tests, context, recordings and settings only.
# Quartet QE itself is installed once per machine, not copied into each repo. Usage: ./new-tests-repo.sh ../my-product-tests
set -euo pipefail
HERE="$(cd "$(dirname "$0")" && pwd)"
TARGET="${1:?Usage: ./new-tests-repo.sh <new-folder>}"
[ -e "$TARGET" ] && { echo "$TARGET already exists."; exit 1; }
URL="$(git -C "$HERE" remote get-url origin)"
NAME="$(basename "$TARGET")"

# The version CI should fetch. Use this checkout's commit if it has been pushed, otherwise the pushed main.
REF="$(git -C "$HERE" rev-parse HEAD)"
if [ -z "$(git -C "$HERE" branch -r --contains "$REF" 2>/dev/null)" ]; then
  REF="$(git -C "$HERE" rev-parse origin/main 2>/dev/null || echo main)"
  echo "Note: this Quartet checkout has changes that are not pushed, so CI will use the pushed version ($REF)." >&2
fi

"$HERE/install-quartet.sh"

mkdir -p "$TARGET/context"
cp -R "$HERE/skeleton/." "$TARGET/"
cp -R "$HERE/gradle" "$HERE/gradlew" "$HERE/gradlew.bat" "$HERE/connectors.yaml" "$TARGET/"
cp -R "$HERE/context/engineering-principles" "$HERE/context/playbooks" "$HERE/context/README.md" "$TARGET/context/"
cp "$HERE/get-started.sh" "$HERE/set-secret.sh" "$TARGET/"
perl -pi -e "s|__NAME__|$NAME|g; s|__QUARTET_URL__|${URL%.git}|g; s|__QUARTET_REF__|$REF|g" "$TARGET/README.md" "$TARGET/settings.gradle.kts" "$TARGET/quartet.yaml"
chmod +x "$TARGET/quartet-qe" "$TARGET/gradlew" "$TARGET/get-started.sh" "$TARGET/set-secret.sh"

cd "$TARGET"
git init -q -b main
git add -A
echo "Created $NAME. Next: cd into it and run ./get-started.sh"
