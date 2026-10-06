// Discord Rich Presence ("Playing Catgirl Launcher" on your Discord profile).
// Talks to the Discord desktop app over its local IPC pipe; no extra libraries needed.
// Protocol: frames of [op int32 LE][length int32 LE][JSON]. op 0 = handshake, 1 = command.
const net = require('net');
const path = require('path');
const os = require('os');
const crypto = require('crypto');

function pipePath(i) {
  if (process.platform === 'win32') return `\\\\?\\pipe\\discord-ipc-${i}`;
  const base = process.env.XDG_RUNTIME_DIR || process.env.TMPDIR || process.env.TMP || process.env.TEMP || os.tmpdir();
  return path.join(base, `discord-ipc-${i}`);
}

function encode(op, data) {
  const json = Buffer.from(JSON.stringify(data));
  const head = Buffer.alloc(8);
  head.writeInt32LE(op, 0);
  head.writeInt32LE(json.length, 4);
  return Buffer.concat([head, json]);
}

class DiscordPresence {
  constructor(clientId, { pathFor = pipePath, retryMs = 15000 } = {}) {
    this.clientId = clientId;
    this.pathFor = pathFor;
    this.retryMs = retryMs;
    this.sock = null;
    this.ready = false;
    this.activity = null;   // last activity we want shown
    this.enabled = false;
    this.timer = null;
  }

  get configured() { return /^\d{15,25}$/.test(String(this.clientId || '')); }

  start() {
    if (!this.configured || this.enabled) return;
    this.enabled = true;
    this.connect();
  }

  stop() {
    this.enabled = false;
    clearTimeout(this.timer);
    if (this.sock) { try { this.sock.destroy(); } catch {} }
    this.sock = null;
    this.ready = false;
  }

  // Try pipes 0..9 until one answers (Discord, PTB and Canary each take one).
  connect(i = 0) {
    if (!this.enabled) return;
    if (i > 9) { this.retry(); return; }
    const sock = net.createConnection(this.pathFor(i));
    let opened = false;
    sock.once('connect', () => {
      opened = true;
      this.sock = sock;
      let buf = Buffer.alloc(0);
      sock.on('data', (d) => {
        buf = Buffer.concat([buf, d]);
        while (buf.length >= 8) {
          const op = buf.readInt32LE(0);
          const len = buf.readInt32LE(4);
          if (buf.length < 8 + len) break;
          let msg = null;
          try { msg = JSON.parse(buf.toString('utf8', 8, 8 + len)); } catch {}
          buf = buf.subarray(8 + len);
          this.onMessage(op, msg);
        }
      });
      sock.on('close', () => { this.sock = null; this.ready = false; this.retry(); });
      sock.write(encode(0, { v: 1, client_id: String(this.clientId) }));
    });
    sock.once('error', () => { if (!opened) this.connect(i + 1); });
  }

  retry() {
    clearTimeout(this.timer);
    if (this.enabled) this.timer = setTimeout(() => this.connect(), this.retryMs);
  }

  onMessage(op, msg) {
    if (op === 1 && msg?.evt === 'READY') {
      this.ready = true;
      this.send();
    } else if (op === 2) { // Discord closed the connection (e.g. bad client id)
      try { this.sock?.destroy(); } catch {}
    }
  }

  set(activity) {
    this.activity = activity;
    this.send();
  }

  send() {
    if (!this.ready || !this.sock) return;
    this.sock.write(encode(1, {
      cmd: 'SET_ACTIVITY',
      args: { pid: process.pid, activity: this.activity || undefined },
      nonce: crypto.randomUUID(),
    }));
  }
}

// What we show for each launcher state.
function buildActivity({ playing, inst, server, startedAt, showServer, downloadUrl }) {
  const a = {
    assets: { large_image: 'logo', large_text: 'Catgirl Launcher' },
    instance: false,
  };
  if (playing && inst) {
    const loader = inst.loader === 'fabric' ? `Fabric${inst.loaderVersion ? ' ' + inst.loaderVersion : ''}` : 'Vanilla';
    a.details = showServer && server ? `Playing on ${server}` : 'Playing Minecraft';
    a.state = `Minecraft ${inst.mcVersion} / ${loader}`;
    if (startedAt) a.timestamps = { start: Math.floor(startedAt / 1000) };
  } else {
    a.details = 'In the launcher';
    a.state = 'Picking an instance';
  }
  if (downloadUrl) a.buttons = [{ label: 'Get Catgirl Launcher', url: downloadUrl }];
  return a;
}

module.exports = { DiscordPresence, buildActivity, encode, pipePath };
