const fs = require('fs');
const path = require('path');
const crypto = require('crypto');
const { Readable } = require('stream');
const { pipeline } = require('stream/promises');

const UA = 'CatgirlLauncher/0.1.0 (github.com/jerrix/catgirl-launcher)';

async function fetchJson(url, opts = {}) {
  const res = await fetch(url, { ...opts, headers: { 'User-Agent': UA, Accept: 'application/json', ...(opts.headers || {}) } });
  const text = await res.text();
  let body;
  try { body = text ? JSON.parse(text) : null; } catch { body = text; }
  if (!res.ok) {
    const err = new Error(`${opts.method || 'GET'} ${url} failed (${res.status})`);
    err.status = res.status;
    err.body = body;
    throw err;
  }
  return body;
}

function sha1File(file) {
  return new Promise((resolve, reject) => {
    const h = crypto.createHash('sha1');
    fs.createReadStream(file).on('error', reject).on('data', (d) => h.update(d)).on('end', () => resolve(h.digest('hex')));
  });
}

async function fileOk(file, sha1, size) {
  try {
    const st = fs.statSync(file);
    if (size != null && st.size !== size) return false;
    if (sha1) return (await sha1File(file)) === sha1;
    return st.size > 0;
  } catch { return false; }
}

// Download one file (skips if already valid). Retries a few times.
async function download(url, dest, { sha1, size } = {}) {
  if (await fileOk(dest, sha1, size)) return false;
  fs.mkdirSync(path.dirname(dest), { recursive: true });
  let lastErr;
  for (let attempt = 0; attempt < 3; attempt++) {
    try {
      const res = await fetch(url, { headers: { 'User-Agent': UA } });
      if (!res.ok || !res.body) throw new Error(`Download failed (${res.status}): ${url}`);
      const tmp = dest + '.part';
      await pipeline(Readable.fromWeb(res.body), fs.createWriteStream(tmp));
      if (sha1 && (await sha1File(tmp)) !== sha1) throw new Error(`Checksum mismatch: ${url}`);
      fs.renameSync(tmp, dest);
      return true;
    } catch (e) { lastErr = e; }
  }
  throw lastErr;
}

// Download many files with limited parallelism, reporting progress.
async function downloadAll(jobs, onProgress, concurrency = 16) {
  let done = 0;
  const total = jobs.length;
  let i = 0;
  const worker = async () => {
    while (i < jobs.length) {
      const job = jobs[i++];
      await download(job.url, job.path, job);
      done++;
      if (onProgress && (done % 10 === 0 || done === total)) onProgress(done, total);
    }
  };
  await Promise.all(Array.from({ length: Math.min(concurrency, jobs.length) }, worker));
}

module.exports = { UA, fetchJson, download, downloadAll, sha1File };
