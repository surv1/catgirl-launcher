const { contextBridge, ipcRenderer } = require('electron');

// Every call returns the data, or throws an Error with a readable message.
async function call(channel, ...args) {
  const r = await ipcRenderer.invoke(channel, ...args);
  if (!r.ok) throw new Error(r.error);
  return r.data;
}

const on = (channel) => (cb) => {
  const fn = (_e, payload) => cb(payload);
  ipcRenderer.on(channel, fn);
  return () => ipcRenderer.removeListener(channel, fn);
};

contextBridge.exposeInMainWorld('cat', {
  win: {
    minimize: () => ipcRenderer.send('win:minimize'),
    maximize: () => ipcRenderer.send('win:maximize'),
    close: () => ipcRenderer.send('win:close'),
  },
  info: () => call('app:info'),
  openExternal: (url) => call('app:openExternal', url),
  copy: (text) => call('app:copy', text),
  ping: (address) => call('server:ping', address),
  bg: {
    get: () => call('bg:get'),
    pickFile: () => call('bg:pickFile'),
    fromUrl: (url) => call('bg:fromUrl', url),
  },
  font: {
    get: () => call('font:get'),
    pickFile: () => call('font:pickFile'),
  },
  packs: {
    search: (q, offset) => call('packs:search', q, offset),
    install: (projectId) => call('packs:install', projectId),
    onProgress: on('pack:progress'),
  },
  update: {
    check: () => call('update:check'),
    install: () => call('update:install'),
    onStatus: on('update:status'),
  },
  settings: {
    get: () => call('settings:get'),
    set: (s) => call('settings:set', s),
    pickJava: () => call('settings:pickJava'),
  },
  auth: {
    list: () => call('auth:list'),
    start: () => call('auth:start'),
    poll: (d) => call('auth:poll', d),
    cancel: () => call('auth:cancel'),
    select: (uuid) => call('auth:select', uuid),
    remove: (uuid) => call('auth:remove', uuid),
  },
  versions: {
    list: () => call('versions:list'),
    fabric: (mc) => call('versions:fabric', mc),
  },
  instances: {
    list: () => call('inst:list'),
    create: (d) => call('inst:create', d),
    update: (id, d) => call('inst:update', id, d),
    remove: (id) => call('inst:remove', id),
    openFolder: (id) => call('inst:openFolder', id),
  },
  mods: {
    search: (id, q, offset) => call('mods:search', id, q, offset),
    install: (id, pid) => call('mods:install', id, pid),
    list: (id) => call('mods:list', id),
    toggle: (id, file) => call('mods:toggle', id, file),
    remove: (id, file) => call('mods:remove', id, file),
    migrate: (id) => call('mods:migrate', id),
    onProgress: on('mods:progress'),
  },
  launch: {
    start: (id) => call('launch:start', id),
    kill: (id) => call('launch:kill', id),
    onProgress: on('launch:progress'),
    onLog: on('launch:log'),
    onStarted: on('launch:started'),
    onExit: on('launch:exit'),
    onServer: on('launch:server'),
  },
});
