// Downloads a private Java runtime (Eclipse Temurin) per major version,
// so players never have to install Java themselves.
const fs = require('fs');
const path = require('path');
const { execFile } = require('child_process');
const paths = require('./paths');
const { download } = require('./net');
const { extractZip } = require('./unzip');

function osName() {
  return process.platform === 'win32' ? 'windows' : process.platform === 'darwin' ? 'mac' : 'linux';
}
function archName() {
  return process.arch === 'arm64' ? 'aarch64' : process.arch === 'ia32' ? 'x32' : 'x64';
}

function javaExe(home) {
  const win = process.platform === 'win32';
  return path.join(home, 'bin', win ? 'javaw.exe' : 'java');
}

// Look for bin/java inside an extracted JDK/JRE folder (handles mac Contents/Home layout).
function findJavaHome(dir) {
  if (!fs.existsSync(dir)) return null;
  const candidates = [dir];
  for (const sub of fs.readdirSync(dir)) {
    const p = path.join(dir, sub);
    candidates.push(p, path.join(p, 'Contents', 'Home'));
  }
  for (const c of candidates) if (fs.existsSync(javaExe(c))) return c;
  return null;
}

function run(cmd, args) {
  return new Promise((resolve, reject) => {
    execFile(cmd, args, { windowsHide: true }, (err, stdout, stderr) => (err ? reject(new Error(stderr || err.message)) : resolve(stdout)));
  });
}

async function ensureJava(major, onStatus = () => {}) {
  const dir = path.join(paths.dirs().runtime, `java-${major}`);
  const existing = findJavaHome(dir);
  if (existing) return javaExe(existing);

  let arch = archName();
  // Java 8 has no Apple Silicon build from Adoptium; Rosetta runs the x64 one.
  if (major === 8 && osName() === 'mac' && arch === 'aarch64') arch = 'x64';

  const isWin = osName() === 'windows';
  const archive = path.join(paths.dirs().runtime, `java-${major}${isWin ? '.zip' : '.tar.gz'}`);
  const url = `https://api.adoptium.net/v3/binary/latest/${major}/ga/${osName()}/${arch}/jre/hotspot/normal/eclipse`;

  onStatus(`Downloading Java ${major}…`);
  try {
    await download(url, archive);
  } catch {
    // Some versions only ship as a JDK.
    await download(url.replace('/jre/', '/jdk/'), archive);
  }

  onStatus(`Unpacking Java ${major}…`);
  fs.rmSync(dir, { recursive: true, force: true });
  fs.mkdirSync(dir, { recursive: true });
  // Windows gets a .zip (unpacked by our own code); macOS/Linux get a .tar.gz (system tar).
  if (isWin) extractZip(archive, dir);
  else await run('tar', ['-xzf', archive, '-C', dir]);
  fs.rmSync(archive, { force: true });

  const home = findJavaHome(dir);
  if (!home) throw new Error(`Java ${major} downloaded but no java executable was found.`);
  if (!isWin) fs.chmodSync(javaExe(home), 0o755);
  return javaExe(home);
}

module.exports = { ensureJava };
