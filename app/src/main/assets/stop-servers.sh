#!/data/data/com.termux/files/usr/bin/bash
# Stops the embedded servers (toolbar Stop action). Leaves the setup marker
# alone, so the next start just restarts the servers.
BASE="$HOME/.anymodelbot"
for p in anymodelbot terminal; do
  if [ -f "$BASE/$p.pid" ]; then
    pid=$(cat "$BASE/$p.pid" 2>/dev/null)
    [ -n "$pid" ] && kill "$pid" 2>/dev/null || true
    rm -f "$BASE/$p.pid"
  fi
done
pkill -f "tsx/dist/cli.mjs" 2>/dev/null || true
pkill -f "terminal/bridge.js" 2>/dev/null || true
rm -f "$BASE/web-url.txt" "$BASE/terminal-up" "$BASE/ovis-up"
echo "stopped" > "$BASE/status.txt"
echo "servers stopped $(date)" >> "$BASE/setup.log"
