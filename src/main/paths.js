const path = require('path');
const fs = require('fs');

let base = null;

// Everything lives in one folder, e.g. %APPDATA%\CatgirlLauncher on Windows.
function init(appDataDir) {
  base = path.join(appDataDir, 'CatgirlLauncher');
  for (const d of [base, dirs().versions, dirs().libraries, dirs().assets, dirs().runtime, dirs().instances, dirs().cache]) {
    fs.mkdirSync(d, { recursive: true });
  }
}

function dirs() {
  if (!base) throw new Error('paths not initialised');
  return {
    base,
    versions: path.join(base, 'versions'),
    libraries: path.join(base, 'libraries'),
    assets: path.join(base, 'assets'),
    runtime: path.join(base, 'runtime'),
    instances: path.join(base, 'instances'),
    cache: path.join(base, 'cache'),
    accounts: path.join(base, 'accounts.json'),
    settings: path.join(base, 'settings.json'),
  };
}

function readJson(file, fallback) {
  try { return JSON.parse(fs.readFileSync(file, 'utf8')); } catch { return fallback; }
}

function writeJson(file, data) {
  fs.mkdirSync(path.dirname(file), { recursive: true });
  const tmp = file + '.tmp';
  fs.writeFileSync(tmp, JSON.stringify(data, null, 2));
  fs.renameSync(tmp, file);
}

module.exports = { init, dirs, readJson, writeJson };
