#!/usr/bin/env bash
# Saves one secret (a token or password) to .env in this folder. The value is not shown as you type it,
# is never printed, and .env is never committed. Quartet QE never asks for secrets in a chat.
#   ./set-secret.sh JIRA_TOKEN
cd "$(dirname "$0")" || exit 1
NAME="${1:-}"
while [ -z "$NAME" ]; do
  printf 'Name of the secret, for example JIRA_TOKEN: '
  read -r NAME
done
case "$NAME" in
  [A-Z]*) ;;
  *) echo "A secret name is capital letters, digits and underscores, such as JIRA_TOKEN."; exit 1 ;;
esac
if printf '%s' "$NAME" | grep -q '[^A-Z0-9_]'; then
  echo "A secret name is capital letters, digits and underscores, such as JIRA_TOKEN."; exit 1
fi
printf 'Paste the value for %s and press Enter (it will not be shown): ' "$NAME"
read -rs VALUE
echo
if [ -z "$VALUE" ]; then echo "Nothing entered. Nothing was changed."; exit 1; fi
umask 077
touch .env
{ grep -v "^${NAME}=" .env 2>/dev/null || true; printf '%s=%s\n' "$NAME" "$VALUE"; } > .env.tmp && mv .env.tmp .env
echo "Saved $NAME to .env (${#VALUE} characters). Tell your AI it is done."
