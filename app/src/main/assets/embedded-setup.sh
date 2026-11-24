#!/data/data/com.termux/files/usr/bin/bash
# Embedded-apps first-run setup for the Termux-based APK.
# Installs Node.js, AnyModelBot and Ovis inside Termux, then starts their servers.
# Idempotent: safe to run again. Logs to $BASE/setup.log. Never prints secrets.
BASE="$HOME/.anymodelbot"
LOG="$BASE/setup.log"
STATUS="$BASE/status.txt"
MARKER="$BASE/setup-done"
mkdir -p "$BASE" "$BASE/terminal"
exec >>"$LOG" 2>&1
echo "=== embedded setup $(date) ==="
set_status() { echo "$1" > "$STATUS"; echo "STATUS: $1"; }
set_status "setup running"

# ---------- Node.js >= 22 ----------
node_ok() {
  command -v node >/dev/null 2>&1 || return 1
  major=$(node --version 2>/dev/null | sed 's/^v//; s/\..*//')
  [ -n "$major" ] && [ "$major" -ge 22 ] 2>/dev/null
}
if ! node_ok; then
  echo "Installing nodejs + git via pkg..."
  pkg update -y && pkg install -y nodejs git || echo "pkg install had errors, checking node anyway"
fi
if ! node_ok; then
  echo "pkg node missing/too old, trying official Node 22 tarball..."
  arch=$(uname -m)
  case "$arch" in
    aarch64) node_arch=arm64 ;;
    *) echo "Unsupported arch for tarball: $arch"; set_status "error: unsupported arch $arch"; exit 1 ;;
  esac
  ver=$(curl -fsSL https://nodejs.org/dist/index.json 2>/dev/null | grep -o '"version":"v22[^"]*"' | head -1 | sed 's/"version":"//; s/"//')
  if [ -z "$ver" ]; then echo "Could not resolve Node 22 version"; set_status "error: node resolve failed"; exit 1; fi
  cd "$BASE" || exit 1
  curl -fsSL -o "node-$ver-linux-$node_arch.tar.xz" "https://nodejs.org/dist/$ver/node-$ver-linux-$node_arch.tar.xz" || { set_status "error: node download failed"; exit 1; }
  tar -xf "node-$ver-linux-$node_arch.tar.xz" || { set_status "error: node extract failed"; exit 1; }
  echo "export PATH=\"$BASE/node-$ver-linux-$node_arch/bin:\$PATH\"" > "$BASE/env-path.sh"
  echo "Node from tarball: $ver"
fi
node --version
npm --version

# ---------- AnyModelBot ----------
set_status "setting up anymodelbot..."
if [ -d "$BASE/anymodelbot/.git" ]; then
  git -C "$BASE/anymodelbot" pull --ff-only || echo "anymodelbot pull failed, keeping existing checkout"
else
  git clone --depth 1 https://github.com/modelflare-ai/AnyModelBot.git "$BASE/anymodelbot" || { set_status "error: anymodelbot clone failed"; exit 1; }
fi
cd "$BASE/anymodelbot" || exit 1
npm install || { set_status "error: anymodelbot npm install failed"; exit 1; }
npm run build || { set_status "error: anymodelbot build failed"; exit 1; }

# ---------- Bun (for Ovis) ----------
if ! command -v bun >/dev/null 2>&1; then
  curl -fsSL https://bun.sh/install | bash || echo "bun install failed (ovis will be skipped)"
  export PATH="$HOME/.bun/bin:$PATH"
fi

# ---------- Ovis (best effort) ----------
if command -v bun >/dev/null 2>&1; then
  set_status "setting up ovis..."
  if [ -d "$BASE/ovis/.git" ]; then
    git -C "$BASE/ovis" pull --ff-only || echo "ovis pull failed, keeping existing checkout"
  else
    git clone --depth 1 https://github.com/devswha/ovis.git "$BASE/ovis" || echo "ovis clone failed"
  fi
  if [ -d "$BASE/ovis" ]; then
    cd "$BASE/ovis" || true
    bun install || echo "ovis bun install failed"
    bun run build || echo "ovis build failed"
  fi
fi

# ---------- Terminal bridge deps ----------
set_status "setting up terminal..."
cd "$BASE/terminal" || exit 1
npm init -y >/dev/null 2>&1 || true
npm install jquery.terminal || echo "jquery.terminal install failed (terminal tab may not load)"

# ---------- Env + start script ----------
cat > "$BASE/env.sh" <<'EOF'
export AMB_LANG=en
export AMB_ALLOW_LANDLOCK=1
export AMB_MAGPIE_BASE_URL=https://api.xkiro.com/v1
export AMB_DEFAULT_MODEL=mistralai/mistral-small-2603
EOF
cat > "$BASE/start-servers.sh" <<'EOS'
#!/data/data/com.termux/files/usr/bin/bash
# Starts AnyModelBot + Ovis + terminal bridge. Idempotent, restart-capable.
BASE="$HOME/.anymodelbot"
[ -f "$BASE/env-path.sh" ] && . "$BASE/env-path.sh"
[ -f "$BASE/env.sh" ] && . "$BASE/env.sh"
if [ -f "$BASE/xkiro-key.txt" ]; then export AMB_MAGPIE_API_KEY="$(cat "$BASE/xkiro-key.txt" | tr -d ' \n\r')"; fi
# Stop previous instances (pid files + pattern match, node/npm may orphan children)
for p in anymodelbot ovis terminal; do
  if [ -f "$BASE/$p.pid" ]; then
    pid=$(cat "$BASE/$p.pid" 2>/dev/null)
    [ -n "$pid" ] && kill "$pid" 2>/dev/null || true
  fi
done
pkill -f "tsx src/main.ts" 2>/dev/null || true
pkill -f "server/managed.ts" 2>/dev/null || true
pkill -f "terminal/bridge.js" 2>/dev/null || true
sleep 1
echo "starting" > "$BASE/status.txt"
rm -f "$BASE/web-url.txt"
# AnyModelBot (port 3600, token file data/ui-token)
if [ -d "$BASE/anymodelbot" ]; then
  cd "$BASE/anymodelbot" || exit 1
  nohup npm start > "$BASE/anymodelbot.log" 2>&1 &
  echo $! > "$BASE/anymodelbot.pid"
  for i in $(seq 1 60); do
    if [ -f "$BASE/anymodelbot/data/ui-token" ]; then
      token=$(cat "$BASE/anymodelbot/data/ui-token" | tr -d ' \n\r')
      if [ -n "$token" ]; then echo "http://127.0.0.1:3600?token=$token" > "$BASE/web-url.txt"; break; fi
    fi
    sleep 2
  done
fi
# Ovis server (port 7317, best effort)
if command -v bun >/dev/null 2>&1 && [ -d "$BASE/ovis" ]; then
  cd "$BASE/ovis" || true
  PORT=7317 nohup bun run server/managed.ts > "$BASE/ovis.log" 2>&1 &
  echo $! > "$BASE/ovis.pid"
fi
# Terminal bridge (port 3602, best effort)
if [ -f "$BASE/terminal/bridge.js" ]; then
  cd "$BASE/terminal" || true
  nohup node bridge.js > "$BASE/terminal.log" 2>&1 &
  echo $! > "$BASE/terminal.pid"
fi
echo "ready" > "$BASE/status.txt"
EOS
chmod +x "$BASE/start-servers.sh"

# ---------- Start everything, mark done ----------
bash "$BASE/start-servers.sh"
touch "$MARKER"
set_status "setup done"
echo "=== embedded setup finished $(date) ==="
