// Tiny loopback-only terminal server for the embedded APK.
// Serves a jquery.terminal page on 127.0.0.1:3602 and POST /exec to run commands.
// No auth: it only listens on loopback, inside the app.
const http = require('http');
const fs = require('fs');
const path = require('path');
const { spawn } = require('child_process');

const PORT = 3602;
const HOST = '127.0.0.1';
// v2: the static files ship in the APK assets and are copied next to this
// script by the app — no npm install on the phone.
const ROOT = __dirname;

function resolveStatic(rel) {
  const p = path.join(ROOT, rel);
  return fs.existsSync(p) ? p : null;
}

const FILES = {
  '/jquery.min.js': resolveStatic('jquery.min.js'),
  '/jquery.terminal.min.js': resolveStatic('jquery.terminal.min.js'),
  '/jquery.terminal.min.css': resolveStatic('jquery.terminal.min.css'),
};

const PAGE = `<!doctype html>
<html><head><meta charset="utf-8"><meta name="viewport" content="width=device-width, initial-scale=1">
<title>Terminal</title>
<link rel="stylesheet" href="/jquery.terminal.min.css">
<style>html,body{height:100%;margin:0;background:#000} #term{height:100%} </style>
</head><body>
<div id="term"></div>
<script src="/jquery.min.js"></script>
<script src="/jquery.terminal.min.js"></script>
<script>
$('#term').terminal(function(command) {
  var term = this;
  if (!command) return;
  term.pause();
  fetch('/exec', {method: 'POST', headers: {'Content-Type': 'application/json'},
    body: JSON.stringify({cmd: command})})
    .then(function(r) { return r.json(); })
    .then(function(d) {
      if (d.stdout) term.echo(d.stdout);
      if (d.stderr) term.error(d.stderr);
      if (d.code !== 0) term.error('exit code ' + d.code);
      term.resume();
    })
    .catch(function(e) { term.error(String(e)); term.resume(); });
}, { greetings: 'Embedded terminal - commands run in Termux', prompt: '$ ' });
</script>
</body></html>`;

const server = http.createServer((req, res) => {
  if (req.url === '/' || req.url === '/index.html') {
    res.writeHead(200, { 'Content-Type': 'text/html' });
    res.end(PAGE);
    return;
  }
  if (req.method === 'GET' && FILES[req.url]) {
    const file = FILES[req.url];
    const type = req.url.endsWith('.css') ? 'text/css' : 'application/javascript';
    res.writeHead(200, { 'Content-Type': type });
    fs.createReadStream(file).pipe(res);
    return;
  }
  if (req.url === '/exec' && req.method === 'POST') {
    let body = '';
    req.on('data', (c) => { body += c; if (body.length > 8192) req.destroy(); });
    req.on('end', () => {
      let cmd = '';
      try { cmd = JSON.parse(body).cmd || ''; } catch (e) { /* ignore */ }
      if (!cmd || typeof cmd !== 'string') {
        res.writeHead(400, { 'Content-Type': 'application/json' });
        res.end(JSON.stringify({ stdout: '', stderr: 'empty command', code: 1 }));
        return;
      }
      const child = spawn('bash', ['-lc', cmd], { cwd: process.env.HOME || '/', timeout: 30000 });
      let stdout = '';
      let stderr = '';
      child.stdout.on('data', (d) => { stdout += d; });
      child.stderr.on('data', (d) => { stderr += d; });
      child.on('error', (e) => {
        res.writeHead(200, { 'Content-Type': 'application/json' });
        res.end(JSON.stringify({ stdout: '', stderr: String(e), code: 1 }));
      });
      child.on('close', (code) => {
        res.writeHead(200, { 'Content-Type': 'application/json' });
        res.end(JSON.stringify({ stdout: stdout, stderr: stderr, code: code === null ? 124 : code }));
      });
    });
    return;
  }
  res.writeHead(404, { 'Content-Type': 'text/plain' });
  res.end('not found');
});

server.listen(PORT, HOST, () => {
  console.log('terminal bridge on http://' + HOST + ':' + PORT);
  console.log('static files: ' + JSON.stringify(Object.fromEntries(Object.entries(FILES).map(([k, v]) => [k, !!v]))));
});
