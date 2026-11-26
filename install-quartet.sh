#!/usr/bin/env bash
# Makes this copy of Quartet QE available to every tests repo on this machine.
# Tests repos hold only tests. They find Quartet at ~/.quartet/current (or $QUARTET_HOME), so none of them
# carries a copy of the framework. Safe to run again: it rebuilds if the source changed and refreshes the link.
#
# If ~/.quartet/current already points at another copy, every tests repo on the machine uses that copy, so this
# never switches it silently: it asks when someone is at the keyboard, and otherwise keeps the current one and
# says how to switch. Pass --switch to switch without asking.
set -uo pipefail
cd "$(dirname "$0")"
SWITCH=0
case "${1:-}" in
  --switch) SWITCH=1 ;;
  "") ;;
  *) echo "Usage: ./install-quartet.sh [--switch]" >&2; exit 2 ;;
esac

./gradlew -q :quartet-generator:installDist >&2 || { echo "Could not build Quartet QE." >&2; exit 1; }
TARGET="$PWD/quartet-generator/build/install/quartet-qe"
HOME_DIR="${QUARTET_HOME_LINK:-$HOME/.quartet}"
LINK="$HOME_DIR/current"
mkdir -p "$HOME_DIR"

resolve() { (cd "$1" 2>/dev/null && pwd -P); }

if [ -L "$LINK" ] || [ -e "$LINK" ]; then
  CURRENT="$(resolve "$LINK")"
  # A link to a copy that no longer exists is repaired without asking: nothing can be using it.
  if [ -n "$CURRENT" ] && [ "$CURRENT" != "$(resolve "$TARGET")" ] && [ "$SWITCH" -eq 0 ]; then
    echo "Quartet QE is already installed on this machine from another copy:" >&2
    echo "  in use:    $CURRENT" >&2
    echo "  this copy: $TARGET" >&2
    echo "Every tests repo on this machine uses the one in use." >&2
    answer=""
    if [ -t 0 ] && [ -t 2 ]; then
      printf "Switch every tests repo to this copy? [y/N] " >&2
      read -r answer || answer=""
    fi
    case "$answer" in
      y|Y|yes|YES) ;;
      *)
        echo "Kept the one in use. To switch later: ./install-quartet.sh --switch" >&2
        exit 0 ;;
    esac
  fi
fi

ln -sfn "$TARGET" "$LINK"
echo "Quartet QE is installed for this machine: $LINK -> $TARGET" >&2
