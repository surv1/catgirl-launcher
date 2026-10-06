// Minecraft "Server List Ping": the same request the multiplayer screen makes to show
// a server's icon, MOTD and player count. https://minecraft.wiki/w/Java_Edition_protocol/Server_List_Ping
const net = require('net');
const dns = require('dns').promises;

function varint(n) {
  const out = [];
  do {
    let b = n & 0x7f;
    n >>>= 7;
    if (n !== 0) b |= 0x80;
    out.push(b);
  } while (n !== 0);
  return Buffer.from(out);
}

function readVarint(buf, offset) {
  let value = 0;
  let shift = 0;
  let i = offset;
  while (true) {
    if (i >= buf.length) return null; // need more data
    const b = buf[i++];
    value |= (b & 0x7f) << shift;
    if (!(b & 0x80)) return { value, size: i - offset };
    shift += 7;
    if (shift > 35) throw new Error('VarInt too big');
  }
}

function packet(id, ...parts) {
  const body = Buffer.concat([varint(id), ...parts]);
  return Buffer.concat([varint(body.length), body]);
}

function mcString(s) {
  const b = Buffer.from(s, 'utf8');
  return Buffer.concat([varint(b.length), b]);
}

// Turn a chat component (string or JSON) into plain text.
function plainText(c) {
  if (c == null) return '';
  if (typeof c === 'string') return c.replace(/§./g, '');
  let s = c.text || c.translate || '';
  if (Array.isArray(c.extra)) s += c.extra.map(plainText).join('');
  if (Array.isArray(c)) s = c.map(plainText).join('');
  return s.replace(/§./g, '');
}

function parseAddress(address) {
  const [host, portStr] = address.trim().split(':');
  return { host, port: portStr ? parseInt(portStr, 10) : null };
}

async function resolve(address) {
  const { host, port } = parseAddress(address);
  if (port) return { host, port };
  // Many servers use an SRV record (_minecraft._tcp.example.net).
  try {
    const srv = await dns.resolveSrv(`_minecraft._tcp.${host}`);
    if (srv.length) return { host: srv[0].name, port: srv[0].port, original: host };
  } catch {}
  return { host, port: 25565, original: host };
}

async function ping(address, timeoutMs = 5000) {
  if (!address || !address.trim()) throw new Error('No server address');
  const target = await resolve(address);
  return new Promise((resolvePromise, reject) => {
    const sock = net.connect({ host: target.host, port: target.port });
    let data = Buffer.alloc(0);
    let done = false;
    const finish = (err, val) => {
      if (done) return;
      done = true;
      clearTimeout(timer);
      sock.destroy();
      err ? reject(err) : resolvePromise(val);
    };
    const timer = setTimeout(() => finish(new Error('Server did not respond')), timeoutMs);
    sock.on('error', (e) => finish(new Error(`Can't reach server (${e.code || e.message})`)));
    sock.on('connect', () => {
      const portBuf = Buffer.alloc(2);
      portBuf.writeUInt16BE(target.port);
      const handshake = packet(0x00, varint(767), mcString(target.original || target.host), portBuf, varint(1));
      sock.write(Buffer.concat([handshake, packet(0x00)]));
    });
    sock.on('data', (chunk) => {
      data = Buffer.concat([data, chunk]);
      try {
        const len = readVarint(data, 0);
        if (!len || data.length < len.size + len.value) return;
        let off = len.size;
        const id = readVarint(data, off); off += id.size;
        if (id.value !== 0x00) return finish(new Error('Unexpected reply'));
        const strLen = readVarint(data, off); off += strLen.size;
        const json = JSON.parse(data.toString('utf8', off, off + strLen.value));
        finish(null, {
          motd: plainText(json.description).trim(),
          online: json.players?.online ?? null,
          max: json.players?.max ?? null,
          version: json.version?.name || '',
          favicon: typeof json.favicon === 'string' && json.favicon.startsWith('data:image/png;base64,') ? json.favicon : null,
        });
      } catch (e) {
        finish(new Error(`Bad server reply: ${e.message}`));
      }
    });
  });
}

module.exports = { ping, plainText, varint, readVarint };
