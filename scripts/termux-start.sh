#!/data/data/com.termux/files/usr/bin/bash
set -euo pipefail
cd "$(dirname "$0")/.."
command -v node >/dev/null || { echo 'Install Node first: pkg install nodejs-lts'; exit 1; }
mkdir -p "$HOME/.config/vayu"
TOKEN_FILE="$HOME/.config/vayu/token"
if [ ! -s "$TOKEN_FILE" ]; then
  (umask 077; node -e 'process.stdout.write(require("node:crypto").randomBytes(32).toString("hex"))' > "$TOKEN_FILE")
fi
export VAYU_TOKEN="$(cat "$TOKEN_FILE")"
export HOST=127.0.0.1
export PORT=8787
printf '\nApp relay URL: http://127.0.0.1:8787\nConnection token (paste in app; keep private):\n%s\n\n' "$VAYU_TOKEN"
exec node server.js
