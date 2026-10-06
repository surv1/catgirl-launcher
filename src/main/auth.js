// Microsoft account login using the OAuth "device code" flow:
// the player opens microsoft.com/link, types a short code, and we poll until done.
// Needs an Azure app registration (Personal Microsoft accounts, "Allow public client flows" ON)
// that Mojang has approved for Minecraft login. See README.md.
const { safeStorage } = require('electron');
const paths = require('./paths');
const { fetchJson } = require('./net');

const TENANT = 'consumers';
const SCOPE = 'XboxLive.signin offline_access';

let clientId = '';
function setClientId(id) { clientId = id; }

function form(obj) { return new URLSearchParams(obj).toString(); }

function checkClientId() {
  if (!clientId || clientId.startsWith('PUT-')) {
    throw new Error('No Microsoft client ID set. Add your Azure app ID to config.json (see README).');
  }
}

async function startDeviceLogin() {
  checkClientId();
  const r = await fetchJson(`https://login.microsoftonline.com/${TENANT}/oauth2/v2.0/devicecode`, {
    method: 'POST',
    headers: { 'Content-Type': 'application/x-www-form-urlencoded' },
    body: form({ client_id: clientId, scope: SCOPE }),
  });
  return { userCode: r.user_code, deviceCode: r.device_code, verificationUri: r.verification_uri, interval: r.interval || 5, expiresIn: r.expires_in };
}

let cancelled = false;
function cancelLogin() { cancelled = true; }

async function pollDeviceLogin(deviceCode, interval) {
  cancelled = false;
  let wait = interval * 1000;
  while (!cancelled) {
    await new Promise((r) => setTimeout(r, wait));
    if (cancelled) break;
    try {
      const tok = await fetchJson(`https://login.microsoftonline.com/${TENANT}/oauth2/v2.0/token`, {
        method: 'POST',
        headers: { 'Content-Type': 'application/x-www-form-urlencoded' },
        body: form({ grant_type: 'urn:ietf:params:oauth:grant-type:device_code', client_id: clientId, device_code: deviceCode }),
      });
      const account = await finishLogin(tok.access_token, tok.refresh_token);
      saveAccount(account);
      return account;
    } catch (e) {
      const code = e.body?.error;
      if (code === 'authorization_pending') continue;
      if (code === 'slow_down') { wait += 5000; continue; }
      if (code === 'expired_token') throw new Error('The login code expired. Try again.');
      if (code === 'authorization_declined') throw new Error('Login was declined.');
      throw e;
    }
  }
  throw new Error('Login cancelled.');
}

// Microsoft token -> Xbox Live -> XSTS -> Minecraft token -> profile
async function finishLogin(msAccessToken, msRefreshToken) {
  const xbl = await fetchJson('https://user.auth.xboxlive.com/user/authenticate', {
    method: 'POST',
    headers: { 'Content-Type': 'application/json' },
    body: JSON.stringify({
      Properties: { AuthMethod: 'RPS', SiteName: 'user.auth.xboxlive.com', RpsTicket: `d=${msAccessToken}` },
      RelyingParty: 'http://auth.xboxlive.com',
      TokenType: 'JWT',
    }),
  });
  const uhs = xbl.DisplayClaims.xui[0].uhs;

  let xsts;
  try {
    xsts = await fetchJson('https://xsts.auth.xboxlive.com/xsts/authorize', {
      method: 'POST',
      headers: { 'Content-Type': 'application/json' },
      body: JSON.stringify({
        Properties: { SandboxId: 'RETAIL', UserTokens: [xbl.Token] },
        RelyingParty: 'rp://api.minecraftservices.com/',
        TokenType: 'JWT',
      }),
    });
  } catch (e) {
    const x = e.body?.XErr;
    const msgs = {
      2148916233: "This Microsoft account doesn't have an Xbox profile yet. Sign in at minecraft.net once, then try again.",
      2148916235: 'Xbox Live is not available in your country.',
      2148916238: 'This is a child account. An adult needs to add it to a Microsoft family first.',
    };
    throw new Error(msgs[x] || 'Xbox login failed.');
  }

  let mc;
  try {
    mc = await fetchJson('https://api.minecraftservices.com/authentication/login_with_xbox', {
      method: 'POST',
      headers: { 'Content-Type': 'application/json' },
      body: JSON.stringify({ identityToken: `XBL3.0 x=${uhs};${xsts.Token}` }),
    });
  } catch (e) {
    if (e.status === 403) throw new Error('Mojang has not approved this launcher\'s client ID yet (see README: "Getting login approved").');
    throw e;
  }

  let profile;
  try {
    profile = await fetchJson('https://api.minecraftservices.com/minecraft/profile', {
      headers: { Authorization: `Bearer ${mc.access_token}` },
    });
  } catch (e) {
    if (e.status === 404) throw new Error("This account doesn't own Minecraft Java Edition.");
    throw e;
  }

  return {
    uuid: profile.id,
    name: profile.name,
    skinUrl: profile.skins?.find((s) => s.state === 'ACTIVE')?.url || null,
    xuid: xsts.DisplayClaims?.xui?.[0]?.xid || '0',
    mcToken: mc.access_token,
    mcTokenExpires: Date.now() + (mc.expires_in - 300) * 1000,
    refreshToken: msRefreshToken,
  };
}

// ---- storage (refresh tokens are encrypted with the OS keychain when available) ----
function enc(s) {
  if (safeStorage.isEncryptionAvailable()) return 'enc:' + safeStorage.encryptString(s).toString('base64');
  return 'raw:' + s;
}
function dec(s) {
  if (!s) return s;
  if (s.startsWith('enc:')) return safeStorage.decryptString(Buffer.from(s.slice(4), 'base64'));
  return s.replace(/^raw:/, '');
}

function loadStore() { return paths.readJson(paths.dirs().accounts, { selected: null, accounts: [] }); }
function saveStore(store) { paths.writeJson(paths.dirs().accounts, store); }

function saveAccount(acc) {
  const store = loadStore();
  const stored = { ...acc, mcToken: enc(acc.mcToken), refreshToken: enc(acc.refreshToken) };
  store.accounts = store.accounts.filter((a) => a.uuid !== acc.uuid);
  store.accounts.push(stored);
  store.selected = acc.uuid;
  saveStore(store);
}

function listAccounts() {
  const store = loadStore();
  return { selected: store.selected, accounts: store.accounts.map(({ uuid, name, skinUrl }) => ({ uuid, name, skinUrl })) };
}

function selectAccount(uuid) { const s = loadStore(); s.selected = uuid; saveStore(s); }

function removeAccount(uuid) {
  const s = loadStore();
  s.accounts = s.accounts.filter((a) => a.uuid !== uuid);
  if (s.selected === uuid) s.selected = s.accounts[0]?.uuid || null;
  saveStore(s);
}

// Get a usable account for launching, refreshing tokens when they have expired.
async function getLaunchAccount() {
  const s = loadStore();
  const stored = s.accounts.find((a) => a.uuid === s.selected);
  if (!stored) throw new Error('Sign in with a Microsoft account first.');
  const acc = { ...stored, mcToken: dec(stored.mcToken), refreshToken: dec(stored.refreshToken) };
  if (Date.now() < acc.mcTokenExpires) return acc;

  checkClientId();
  let tok;
  try {
    tok = await fetchJson(`https://login.microsoftonline.com/${TENANT}/oauth2/v2.0/token`, {
      method: 'POST',
      headers: { 'Content-Type': 'application/x-www-form-urlencoded' },
      body: form({ grant_type: 'refresh_token', client_id: clientId, refresh_token: acc.refreshToken, scope: SCOPE }),
    });
  } catch {
    throw new Error(`Login for ${acc.name} expired. Remove the account and sign in again.`);
  }
  const fresh = await finishLogin(tok.access_token, tok.refresh_token || acc.refreshToken);
  saveAccount(fresh);
  return fresh;
}

module.exports = { setClientId, startDeviceLogin, pollDeviceLogin, cancelLogin, listAccounts, selectAccount, removeAccount, getLaunchAccount };
