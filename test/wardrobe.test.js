// The Wardrobe window with a stand-in for Electron: closing the window while the skin site is
// still loading must never crash the launcher (bug in 0.4.0).
const assert = require('assert');
const Module = require('module');
const { EventEmitter } = require('events');

class FakeWC extends EventEmitter {
  constructor() { super(); this.destroyed = false; this.url = 'https://www.minecraftskins.com/'; this.sent = []; this.session = { fetch: async () => ({ ok: false, status: 404 }) };
    this.navigationHistory = { canGoBack: () => false, canGoForward: () => false, goBack() {}, goForward() {} }; }
  isDestroyed() { return this.destroyed; } getURL() { return this.url; } isLoading() { return false; }
  loadURL(u) { this.url = u; this.emit('did-start-loading'); } reload() {} close() { this.destroyed = true; }
  send(ch, p) { if (this.destroyed) throw new Error('Object has been destroyed'); this.sent.push([ch, p]); }
  setWindowOpenHandler() {} loadFile() {}
}
class FakeWin extends EventEmitter {
  constructor() { super(); this.webContents = new FakeWC(); this.destroyed = false; this.contentView = { addChildView() {} }; wins.push(this); }
  isDestroyed() { return this.destroyed; } getContentSize() { return [1240, 800]; } loadFile() {} show() {} focus() {} isMinimized() { return false; }
  setAlwaysOnTop() {} isAlwaysOnTop() { return false; } minimize() {}
  close() { this.destroyed = true; this.webContents.destroyed = true; this.emit('closed'); }
}
const views = [];
const wins = [];
class FakeView { constructor() { this.webContents = new FakeWC(); views.push(this); } setBounds() {} }
const sessionObj = new EventEmitter();
const fakeElectron = { BrowserWindow: FakeWin, WebContentsView: FakeView, Menu: { buildFromTemplate: () => ({ popup() {} }) }, session: { fromPartition: () => sessionObj }, shell: { openExternal() {} } };
const origLoad = Module._load;
Module._load = function (req, ...rest) { return req === 'electron' ? fakeElectron : origLoad.call(this, req, ...rest); };

const wardrobe = require('../src/main/wardrobe');
wardrobe.init({ icon: '', preload: '', renderer: '/tmp' });

wardrobe.open({ fromGame: true });
wardrobe.search('skindex', 'purple');
const firstSite = views[0].webContents;
firstSite.emit('did-stop-loading');
assert.ok(wins[0].webContents.sent.some(([ch]) => ch === 'wardrobe:nav'));
console.log('ok - window opens, searches and reports page loads');

// Close with the × button, then the site panel finishes loading late (the 0.4.0 crash).
assert.doesNotThrow(() => wardrobe.windowCmd('close'));
assert.doesNotThrow(() => { for (const ev of ['did-stop-loading', 'did-navigate', 'did-start-loading']) firstSite.emit(ev); });
assert.doesNotThrow(() => { wardrobe.search('skindex', 'purple'); wardrobe.nav('back'); wardrobe.windowCmd('pin'); wardrobe.windowCmd('minimize'); });
console.log('ok - closing mid-load and late events no longer crash');

// Re-open: late events from the old panel don't touch the new window.
wardrobe.open({ fromGame: false });
wardrobe.search('namemc', 'cat girl');
assert.ok(views[1].webContents.url.includes('namemc.com/minecraft-skins/tag/cat-girl'));
assert.doesNotThrow(() => firstSite.emit('did-stop-loading'));
assert.doesNotThrow(() => wardrobe.windowCmd('close'));
console.log('ok - re-opening after a close works\n\n3 tests passed');
