// Fake Discord desktop app on a local socket: checks handshake, READY and SET_ACTIVITY.
const assert = require('assert');
const net = require('net');
const fs = require('fs');
const os = require('os');
const path = require('path');
const { DiscordPresence, buildActivity, encode } = require('../src/main/discord');

const dir = fs.mkdtempSync(path.join(os.tmpdir(), 'dc-'));
const sockPath = process.platform === 'win32' ? `\\\\?\\pipe\\catgirl-test-${process.pid}` : path.join(dir, 'discord-ipc-0');
const got = [];

const server = net.createServer((sock) => {
  let buf = Buffer.alloc(0);
  sock.on('data', (d) => {
    buf = Buffer.concat([buf, d]);
    while (buf.length >= 8) {
      const op = buf.readInt32LE(0); const len = buf.readInt32LE(4);
      if (buf.length < 8 + len) break;
      const msg = JSON.parse(buf.toString('utf8', 8, 8 + len)); buf = buf.subarray(8 + len);
      got.push({ op, msg });
      if (op === 0) sock.write(encode(1, { cmd: 'DISPATCH', evt: 'READY', data: { v: 1 } }));
    }
  });
});

server.listen(sockPath, async () => {
  const p = new DiscordPresence('1234567890123456789', { pathFor: (i) => (i === 0 ? sockPath : path.join(dir, `none-${i}`)), retryMs: 100 });
  assert.ok(p.configured);
  assert.ok(!new DiscordPresence('PUT-YOUR-DISCORD-APPLICATION-ID-HERE').configured);
  const inst = { mcVersion: '1.21.11', loader: 'fabric', loaderVersion: '0.17.3' };
  p.set(buildActivity({ playing: true, inst, server: 'play.catland.net', startedAt: 1700000000000, showServer: true, downloadUrl: 'https://github.com/surv1/catgirl-launcher/releases/latest' }));
  p.start();
  await new Promise((r) => setTimeout(r, 300));
  p.stop(); server.close();

  assert.strictEqual(got[0].op, 0);
  assert.deepStrictEqual(got[0].msg, { v: 1, client_id: '1234567890123456789' });
  const act = got.find((g) => g.msg.cmd === 'SET_ACTIVITY').msg.args.activity;
  assert.strictEqual(act.details, 'Playing on play.catland.net');
  assert.strictEqual(act.state, 'Minecraft 1.21.11 / Fabric 0.17.3');
  assert.strictEqual(act.timestamps.start, 1700000000);
  assert.strictEqual(act.assets.large_image, 'logo');
  assert.strictEqual(act.buttons[0].label, 'Get Catgirl Launcher');
  const hidden = buildActivity({ playing: true, inst: { mcVersion: '1.21.11', loader: 'vanilla' }, server: 'x.net', showServer: false });
  assert.strictEqual(hidden.details, 'Playing Minecraft');
  assert.strictEqual(hidden.state, 'Minecraft 1.21.11 / Vanilla');
  assert.strictEqual(buildActivity({ playing: false }).details, 'In the launcher');
  console.log('ok - discord rich presence (handshake, ready, activity)\n\n1 test passed');
  fs.rmSync(dir, { recursive: true, force: true });
});
