#!/usr/bin/env bash
# Saves your AI key to .env in this repo. The key is not shown as you paste it, and .env is never committed.
cd "${1:-$(dirname "$0")}" || exit 1
PROVIDER=""
while [ -z "$PROVIDER" ]; do
  printf 'Provider (anthropic, openai, gemini, azure-openai, openai-compatible): '
  read -r PROVIDER
  case "$PROVIDER" in
    anthropic|openai|gemini|azure-openai|openai-compatible) ;;
    *) echo "That is not a provider name. Do not paste your key here."; clear; PROVIDER="" ;;
  esac
done
printf 'Paste your API key and press Enter (it will not be shown): '
read -rs KEY
echo
if [ -z "$KEY" ]; then
  echo "No key entered. Nothing was changed."
  exit 1
fi
MODEL=""
if [ "$PROVIDER" != "anthropic" ]; then
  printf 'Model name (press Enter to leave blank): '
  read -r MODEL
fi
umask 077
printf 'AI_PROVIDER=%s\nAI_API_KEY=%s\nAI_MODEL=%s\n' "$PROVIDER" "$KEY" "$MODEL" > .env
echo "Saved. Key length: ${#KEY} characters."
