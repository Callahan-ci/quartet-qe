#!/usr/bin/env bash
# Makes this copy of Quartet QE available to every tests repo on this machine.
# Tests repos hold only tests. They find Quartet at ~/.quartet/current (or $QUARTET_HOME), so none of them
# carries a copy of the framework. Safe to run again: it rebuilds if the source changed and refreshes the link.
set -uo pipefail
cd "$(dirname "$0")"
./gradlew -q :quartet-generator:installDist >&2 || { echo "Could not build Quartet QE." >&2; exit 1; }
TARGET="$PWD/quartet-generator/build/install/quartet-qe"
HOME_DIR="${QUARTET_HOME_LINK:-$HOME/.quartet}"
mkdir -p "$HOME_DIR"
ln -sfn "$TARGET" "$HOME_DIR/current"
echo "Quartet QE is installed for this machine: $HOME_DIR/current" >&2
