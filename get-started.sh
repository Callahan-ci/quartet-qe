#!/usr/bin/env bash
# Quartet QE: first-time setup and a menu of what to do next. Safe to run any time.
#
#   ./get-started.sh             the guided menu
#   ./get-started.sh --check     only check this machine and build the tool
#   ./get-started.sh --demo      run the demo (no AI key, no Docker)
#   ./get-started.sh --chat-demo run the demo of the chat path (no AI key)
#   ./get-started.sh --connect   how to connect your chat tool
set -uo pipefail
cd "$(dirname "$0")"

STATE_DIR="${XDG_CONFIG_HOME:-$HOME/.config}/quartet-qe"
STATE="$STATE_DIR/started"
FIRST=1; [ -f "$STATE" ] && FIRST=0
FRAMEWORK=0; { [ -d demo-shopflow ] && [ -d quartet-core ]; } && FRAMEWORK=1
HERE="$PWD"

say()  { printf '%s\n' "$*"; }
ok()   { printf '  OK   %s\n' "$*"; }
fix()  { printf '  FIX  %s\n' "$*"; }
info() { printf '  INFO %s\n' "$*"; }

intro() {
  say ""
  say "Welcome to Quartet QE."
  say ""
  say "It writes end-to-end tests from a user story, proves its own work, and gives you"
  say "one page to review. After the first recording the tests replay with no AI."
  say ""
  say "This takes a few minutes. It checks your machine, builds the tool, then"
  say "offers things to try. Nothing is installed outside this folder, and nothing is"
  say "committed or pushed."
  say ""
}

java_major() {
  local out
  out="$("${JAVA_HOME:+$JAVA_HOME/bin/}java" -version 2>&1 | head -1)" || return 1
  printf '%s' "$out" | sed -E 's/.*version "([0-9]+)(\.([0-9]+))?.*/\1/'
}

check_machine() {
  say "Checking this machine"
  local failed=0 major
  if command -v "${JAVA_HOME:+$JAVA_HOME/bin/}java" >/dev/null 2>&1; then
    major="$(java_major)"
    if [ "${major:-0}" -ge 21 ] 2>/dev/null; then ok "Java $major"
    else fix "Java $major is too old. Install JDK 21 or newer (https://adoptium.net), then run this again."; failed=1; fi
  else
    fix "Java was not found. Install JDK 21 or newer (https://adoptium.net), then run this again."; failed=1
  fi
  if command -v git >/dev/null 2>&1; then ok "git"; else fix "git was not found. Install git, then run this again."; failed=1; fi
  if command -v adb >/dev/null 2>&1; then ok "adb found, so Android devices and emulators can be tested"
  else info "adb not found. Only needed to test Android apps. Web and phone browsers work without it."; fi
  if [ -n "$(ls .env 2>/dev/null)" ]; then ok "An AI key is saved in .env"
  else info "No AI key. That is fine: the AI in your chat tool can write the tests, and the demos need none."; fi
  say ""
  return $failed
}

build_tool() {
  say "Building the tool. The first time takes a minute or two."
  if ./quartet-qe doctor; then say ""; return 0; fi
  say ""
  say "The build or the check did not finish. The messages above say what to fix."
  return 1
}

connect() {
  say ""
  say "Connect your chat tool, so the AI you are chatting with writes the tests"
  say ""
  say "Quartet QE is an MCP server. This folder already has a .mcp.json that points at it."
  say ""
  say "  Claude Code:   open this folder in it and approve the quartet-qe server when asked."
  say "                 Or add it yourself:  claude mcp add quartet-qe -- $HERE/quartet-qe mcp"
  say "  Other tools:   add an MCP server named quartet-qe that runs"
  say "                 $HERE/quartet-qe mcp"
  say "                 with this folder as its working directory. Where that setting lives"
  say "                 depends on the tool."
  say ""
  say "Then ask the AI, for example:"
  say "  Help me set up Quartet QE for my product."
  say "  Test my site https://www.example.com using Quartet QE."
  say "  Write tests for stories/MY-STORY.md using Quartet QE."
  say ""
  say "Setting up asks for your URLs, systems and integrations. When it needs a token or password"
  say "it names it and you save it yourself, hidden, in this terminal:  ./set-secret.sh NAME"
  say "Secrets never go through the chat."
  say ""
  say "The first time, it asks where the tests should live and whether you want Jenkins."
  say ""
}

secret() {
  local name
  printf 'Name of the secret, for example JIRA_TOKEN: '
  read -r name || return 0
  [ -n "$name" ] && ./set-secret.sh "$name"
}

demo()     { say ""; ./demo.sh; }
chatdemo() { say ""; ./demo-chat.sh; }

new_repo() {
  local target
  printf 'Folder for your new tests repo [../my-product-tests]: '
  read -r target || return 0
  target="${target:-../my-product-tests}"
  say ""
  if ./new-tests-repo.sh "$target"; then
    say ""
    say "Next: open $target in your chat tool, or run ./get-started.sh inside it."
    say "Nothing has been committed or pushed."
  fi
}

guide() {
  say ""
  say "The full guide is GETTING-STARTED.md in this folder. README.md explains how it all works."
  say ""
}

menu_framework() {
  while true; do
    say "What would you like to do?"
    say "  1) Watch the demo (no AI key, no Docker)"
    say "  2) See the chat path working (no AI key)"
    say "  3) Set up tests for my product (creates a new tests repo)"
    say "  4) Connect my chat tool, and set up my product with the AI"
    say "  5) Save a secret (a token or password), hidden"
    say "  6) Where is the guide?"
    say "  q) Quit"
    printf '> '
    read -r choice || return 0
    case "$choice" in
      1) demo ;; 2) chatdemo ;; 3) new_repo ;; 4) connect ;; 5) secret ;; 6) guide ;;
      q|Q|"") return 0 ;;
      *) say "Choose 1 to 6, or q." ;;
    esac
    say ""
  done
}

menu_tests_repo() {
  while true; do
    say "What would you like to do?"
    say "  1) Connect my chat tool, and set up my product with the AI"
    say "  2) Save a secret (a token or password), hidden"
    say "  3) Add an AI key instead (for unattended runs)"
    say "  4) Set up Jenkins for these tests"
    say "  5) Check my setup again"
    say "  q) Quit"
    printf '> '
    read -r choice || return 0
    case "$choice" in
      1) connect ;;
      2) secret ;;
      3) if [ -x ./set-ai-key.sh ]; then ./set-ai-key.sh; else say "set-ai-key.sh is not in this folder."; fi ;;
      4) ./quartet-qe setup-jenkins ;;
      5) ./quartet-qe doctor ;;
      q|Q|"") return 0 ;;
      *) say "Choose 1 to 5, or q." ;;
    esac
    say ""
  done
}

case "${1:-}" in
  --check)     check_machine || exit 1; build_tool; exit $? ;;
  --demo)      check_machine || exit 1; demo; exit $? ;;
  --chat-demo) check_machine || exit 1; chatdemo; exit $? ;;
  --connect)   connect; exit 0 ;;
  "" ) ;;
  *) say "Unknown option $1. See the top of this file."; exit 1 ;;
esac

[ "$FIRST" -eq 1 ] && intro || say "Welcome back."
check_machine || exit 1
build_tool || exit 1

mkdir -p "$STATE_DIR" && date -u +%Y-%m-%dT%H:%M:%SZ > "$STATE"

if [ ! -t 0 ]; then
  say "Run ./get-started.sh in a terminal for the menu."
  exit 0
fi

if [ "$FRAMEWORK" -eq 1 ]; then
  [ "$FIRST" -eq 1 ] && say "A good first step is the demo. It shows the whole journey in a few minutes." && say ""
  menu_framework
else
  menu_tests_repo
fi
say "Run ./get-started.sh again any time."
