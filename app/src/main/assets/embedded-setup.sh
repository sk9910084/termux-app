#!/data/data/com.termux/files/usr/bin/bash
# Embedded-apps first-run setup for the Termux-based APK (v2, Antigem-shape).
# The AnyModelBot server is a PREBUILT bundle (downloaded + SHA-256 checked by
# the app, see EmbeddedApps.java): this script only extracts it, installs
# Node.js and writes the start scripts. No npm install happens on the phone.
# If the bundle is missing (download failed), it falls back to the source
# build (clone + npm install + build), which is slow but works.
# Ovis is NOT set up: its server is only a bridge to herdr-core, and no
# herdr-core binary is bundled yet. Logs to $BASE/setup.log. Never prints secrets.
BASE="$HOME/.anymodelbot"
LOG="$BASE/setup.log"
STATUS="$BASE/status.txt"
MARKER="$BASE/setup-done"
BUNDLE="$BASE/anymodelbot-bundle-v0.1.47.tgz"
BUNDLE_SHA256="7cf98d917f2cfd5515f3170b08935b7dfa5e6719d7272f57e7b90347d48930c1"
mkdir -p "$BASE" "$BASE/terminal"
exec >>"$LOG" 2>&1
echo "=== embedded setup v2 $(date) ==="
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

# ---------- AnyModelBot (prebuilt bundle, or source fallback) ----------
set_status "setting up anymodelbot..."
if [ -f "$BUNDLE" ]; then
  echo "Extracting prebuilt AnyModelBot bundle..."
  if command -v sha256sum >/dev/null 2>&1; then
    actual=$(sha256sum "$BUNDLE" | cut -d' ' -f1)
    if [ "$actual" != "$BUNDLE_SHA256" ]; then
      echo "Bundle checksum mismatch ($actual), removing it and falling back to source build"
      rm -f "$BUNDLE"
    fi
  fi
fi
if [ -f "$BUNDLE" ]; then
  rm -rf "$BASE/anymodelbot"
  tar -xzf "$BUNDLE" -C "$BASE" || { set_status "error: bundle extract failed"; exit 1; }
  echo "Bundle extracted."
elif [ -d "$BASE/anymodelbot/.git" ]; then
  echo "No bundle, falling back to source build (slow)..."
  git -C "$BASE/anymodelbot" pull --ff-only || echo "anymodelbot pull failed, keeping existing checkout"
  cd "$BASE/anymodelbot" || exit 1
  npm install || { set_status "error: anymodelbot npm install failed"; exit 1; }
  npm run build || { set_status "error: anymodelbot build failed"; exit 1; }
else
  echo "No bundle, falling back to source build (slow)..."
  git clone --depth 1 https://github.com/modelflare-ai/AnyModelBot.git "$BASE/anymodelbot" || { set_status "error: anymodelbot clone failed"; exit 1; }
  cd "$BASE/anymodelbot" || exit 1
  npm install || { set_status "error: anymodelbot npm install failed"; exit 1; }
  npm run build || { set_status "error: anymodelbot build failed"; exit 1; }
fi

# ---------- Ovis (skipped in v2) ----------
echo "Ovis skipped: its server needs herdr-core, which is not bundled yet."
echo "The Ovis tab will say so instead of showing a dead page."

# ---------- Terminal bridge (files come from the APK assets, no npm install) ----------
set_status "setting up terminal..."
ls -la "$BASE/terminal/" || true

# ---------- Env + start script ----------
# The start script reads the key / endpoint / model files each time it runs
# (with the Xkiro defaults), so a key saved in the app applies on restart.
cat > "$BASE/start-servers.sh" <<'EOS'
#!/data/data/com.termux/files/usr/bin/bash
# Starts AnyModelBot + terminal bridge. Idempotent, restart-capable.
BASE="$HOME/.anymodelbot"
[ -f "$BASE/env-path.sh" ] && . "$BASE/env-path.sh"
export AMB_LANG=en
export AMB_ALLOW_LANDLOCK=1
export AMB_MAGPIE_BASE_URL="$(cat "$BASE/xkiro-base-url.txt" 2>/dev/null | tr -d ' \n\r')"
[ -z "$AMB_MAGPIE_BASE_URL" ] && AMB_MAGPIE_BASE_URL="https://api.xkiro.com/v1"
export AMB_DEFAULT_MODEL="$(cat "$BASE/xkiro-model.txt" 2>/dev/null | tr -d ' \n\r')"
[ -z "$AMB_DEFAULT_MODEL" ] && AMB_DEFAULT_MODEL="mistralai/mistral-small-2603"
if [ -f "$BASE/xkiro-key.txt" ]; then export AMB_MAGPIE_API_KEY="$(cat "$BASE/xkiro-key.txt" | tr -d ' \n\r')"; fi
# Stop previous instances (pid files + pattern match, node may orphan children)
for p in anymodelbot terminal; do
  if [ -f "$BASE/$p.pid" ]; then
    pid=$(cat "$BASE/$p.pid" 2>/dev/null)
    [ -n "$pid" ] && kill "$pid" 2>/dev/null || true
  fi
done
pkill -f "tsx/dist/cli.mjs" 2>/dev/null || true
pkill -f "terminal/bridge.js" 2>/dev/null || true
sleep 1
echo "starting" > "$BASE/status.txt"
rm -f "$BASE/web-url.txt" "$BASE/terminal-up" "$BASE/ovis-up"
# AnyModelBot (port 3600, token file data/ui-token).
# tsx is run directly (no `npm start`: its prestart rebuilds the catalog every time).
if [ -d "$BASE/anymodelbot" ]; then
  cd "$BASE/anymodelbot" || exit 1
  nohup node node_modules/tsx/dist/cli.mjs src/main.ts > "$BASE/anymodelbot.log" 2>&1 &
  echo $! > "$BASE/anymodelbot.pid"
  for i in $(seq 1 90); do
    if [ -f "$BASE/anymodelbot/data/ui-token" ]; then
      token=$(cat "$BASE/anymodelbot/data/ui-token" | tr -d ' \n\r')
      if [ -n "$token" ]; then echo "http://127.0.0.1:3600?token=$token" > "$BASE/web-url.txt"; break; fi
    fi
    sleep 2
  done
fi
# Terminal bridge (port 3602). Mark it up only after it answers.
if [ -f "$BASE/terminal/bridge.js" ]; then
  cd "$BASE/terminal" || true
  nohup node bridge.js > "$BASE/terminal.log" 2>&1 &
  echo $! > "$BASE/terminal.pid"
  for i in $(seq 1 30); do
    if curl -fsS --max-time 2 -o /dev/null http://127.0.0.1:3602/ 2>/dev/null; then
      touch "$BASE/terminal-up"
      break
    fi
    sleep 2
  done
fi
echo "ready" > "$BASE/status.txt"
EOS
chmod +x "$BASE/start-servers.sh"

# ---------- Start everything, mark done ----------
bash "$BASE/start-servers.sh"
touch "$MARKER"
set_status "setup done"
echo "=== embedded setup v2 finished $(date) ==="
