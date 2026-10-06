/* A small GIF decoder for cape GIFs: returns every frame fully drawn (with transparency and
   GIF frame disposal applied), so each one can be used on its own.
   decodeGif(Uint8Array) → { width, height, frames: [{ rgba: Uint8ClampedArray, ms }] } */
(function (root) {
  function decodeGif(bytes) {
    const b = bytes;
    let p = 0;
    const u8 = () => b[p++];
    const u16 = () => { const v = b[p] | (b[p + 1] << 8); p += 2; return v; };
    const sig = String.fromCharCode(...b.subarray(0, 6));
    if (sig !== 'GIF87a' && sig !== 'GIF89a') throw new Error("That isn't a GIF.");
    p = 6;
    const width = u16(), height = u16();
    if (!width || !height || width * height > 4096 * 4096) throw new Error('That GIF is too big.');
    const flags = u8(); u8(); u8(); // background index, aspect
    const readTable = (size) => { const t = b.subarray(p, p + size * 3); p += size * 3; return t; };
    const gct = flags & 0x80 ? readTable(1 << ((flags & 7) + 1)) : null;

    const canvas = new Uint8ClampedArray(width * height * 4); // starts fully transparent
    const frames = [];
    let gce = { disposal: 0, delay: 10, transparent: -1 };

    const subBlocks = () => {
      const parts = [];
      let total = 0;
      for (let n = u8(); n > 0; n = u8()) { parts.push(b.subarray(p, p + n)); total += n; p += n; }
      const out = new Uint8Array(total);
      let o = 0;
      for (const part of parts) { out.set(part, o); o += part.length; }
      return out;
    };

    while (p < b.length) {
      const block = u8();
      if (block === 0x3b) break; // trailer
      if (block === 0x21) {
        const label = u8();
        if (label === 0xf9) {
          u8(); // block size (4)
          const f = u8();
          const delay = u16();
          const ti = u8();
          u8(); // terminator
          gce = { disposal: (f >> 2) & 7, delay, transparent: f & 1 ? ti : -1 };
        } else subBlocks();
        continue;
      }
      if (block !== 0x2c) break; // unknown: stop with what we have
      const fx = u16(), fy = u16(), fw = u16(), fh = u16();
      const iflags = u8();
      const table = iflags & 0x80 ? readTable(1 << ((iflags & 7) + 1)) : gct;
      const interlaced = !!(iflags & 0x40);
      const minCode = u8();
      const data = subBlocks();
      const indices = lzw(minCode, data, fw * fh);

      const before = gce.disposal === 3 ? canvas.slice() : null;
      // Draw this frame on top of what's there.
      const rows = interlaced ? interlaceOrder(fh) : null;
      for (let i = 0; i < fh; i++) {
        const y = fy + (rows ? rows[i] : i);
        if (y >= height) continue;
        for (let x = 0; x < fw; x++) {
          const px = fx + x;
          if (px >= width) continue;
          const idx = indices[i * fw + x];
          if (idx === gce.transparent || !table || idx * 3 + 2 >= table.length) continue;
          const o = (y * width + px) * 4;
          canvas[o] = table[idx * 3]; canvas[o + 1] = table[idx * 3 + 1]; canvas[o + 2] = table[idx * 3 + 2]; canvas[o + 3] = 255;
        }
      }
      frames.push({ rgba: canvas.slice(), ms: Math.max(20, (gce.delay || 10) * 10) });
      // Get ready for the next frame.
      if (gce.disposal === 2) {
        for (let y = fy; y < Math.min(height, fy + fh); y++) canvas.fill(0, (y * width + fx) * 4, (y * width + Math.min(width, fx + fw)) * 4);
      } else if (gce.disposal === 3 && before) canvas.set(before);
      gce = { disposal: 0, delay: 10, transparent: -1 };
      if (frames.length >= 400) break;
    }
    if (!frames.length) throw new Error('That GIF has no pictures in it.');
    return { width, height, frames };
  }

  function interlaceOrder(h) {
    const order = [];
    for (const [start, step] of [[0, 8], [4, 8], [2, 4], [1, 2]]) for (let y = start; y < h; y += step) order.push(y);
    return order;
  }

  // GIF's variable-width LZW.
  function lzw(minCode, data, count) {
    const out = new Uint8Array(count);
    const clear = 1 << minCode, eoi = clear + 1;
    const prefix = new Int16Array(4096), suffix = new Uint8Array(4096), first = new Uint8Array(4096), len = new Uint16Array(4096);
    for (let i = 0; i < clear; i++) { prefix[i] = -1; suffix[i] = i; first[i] = i; len[i] = 1; }
    let size = minCode + 1, next = eoi + 1, prev = -1, o = 0, bits = 0, acc = 0, dp = 0;
    while (o < count) {
      while (bits < size) { if (dp >= data.length) return out; acc |= data[dp++] << bits; bits += 8; }
      const code = acc & ((1 << size) - 1);
      acc >>>= size; bits -= size;
      if (code === clear) { size = minCode + 1; next = eoi + 1; prev = -1; continue; }
      if (code === eoi) break;
      let cur = code, extra = false;
      if (code >= next) { // the KwKwK case: previous string plus its own first character
        if (prev < 0) break;
        cur = prev; extra = true;
      }
      const l = len[cur];
      let w = o + l - 1, c = cur;
      while (c >= 0 && w >= o) { if (w < count) out[w] = suffix[c]; c = prefix[c]; w--; }
      if (extra && o + l < count) out[o + l] = first[prev];
      o += l + (extra ? 1 : 0);
      if (prev >= 0 && next < 4096) {
        prefix[next] = prev;
        suffix[next] = extra ? first[prev] : first[code];
        first[next] = first[prev];
        len[next] = len[prev] + 1;
        next++;
        if (next === 1 << size && size < 12) size++;
      }
      prev = code;
    }
    return out;
  }

  root.decodeGif = decodeGif;
  if (typeof module !== 'undefined') module.exports = { decodeGif };
})(typeof window !== 'undefined' ? window : globalThis);
