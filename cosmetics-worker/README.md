# Catgirl cosmetics service

A tiny Cloudflare Worker that remembers which free cosmetics (cat ears, tail, bow) each player
wears, so everyone using Catgirl Client sees them. It never sees anyone's login: players prove they
own their account the same way Minecraft servers check it (Mojang's `join` / `hasJoined`).

## Put it online (free, ~5 minutes, all in the Cloudflare dashboard)

1. **Workers & Pages → Create → Create Worker.** Name it `catgirl-cosmetics`, click **Deploy**,
   then **Edit code**, replace everything with the contents of `worker.js`, and click **Deploy**.
2. **Storage & Databases → KV → Create** a namespace called `catgirl-cosmetics`.
3. Back in the Worker: **Settings → Bindings → Add → KV namespace**.
   Variable name `COSMETICS`, pick the namespace you just made.
4. **Settings → Variables and Secrets → Add**, type **Secret**, name `NONCE_SECRET`,
   value: any long random text (mash the keyboard, 40+ characters).
5. **Settings → Domains & Routes → Add → Custom domain:** `api.catgirlclient.lol`.
6. Visit https://api.catgirlclient.lol/v1, it should say `"ok": true`.

The launcher already points at `https://api.catgirlclient.lol` (see `cosmeticsApi` in `config.json`).

## API

| Call | What it does |
| --- | --- |
| `GET /v1/cosmetics?uuids=a,b,c` | Cosmetics for up to 60 players (cached 30 s) |
| `POST /v1/challenge` | A one-time code, valid 2 minutes |
| `PUT /v1/me` `{ username, nonce, cosmetics }` | Save your cosmetics, after the launcher has "joined" `sha1(nonce)` with Mojang |
