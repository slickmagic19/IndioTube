// QR pairing + LAN sync server for the IndioTube Android app (pure Node —
// no Electron imports, so it can be exercised with plain `node` and vitest).
//
// The main process owns all library data (library.js); this module only
// moves JSON over the LAN while a pairing window is open:
//   GET  /indio/v1/ping                — discovery, no auth
//   GET  /indio/v1/snapshot?token=…    — phone pulls the desktop library
//   POST /indio/v1/merge?token=…       — phone pushes its own additions
//
// Every request carries the one-time random token from the QR. The server
// binds the LAN only while the sync modal is open, auto-stops after 15 quiet
// minutes, and never touches library data itself — merge (add-only, via
// syncCore.js) runs where the data lives.
const http = require('node:http');
const os = require('node:os');
const crypto = require('node:crypto');

const PROTOCOL_VERSION = 1;
const MAX_BODY_BYTES = 12 * 1024 * 1024;
const SESSION_TTL_MS = 15 * 60 * 1000;
// Awitize uses 47710; stay one clear so both apps can pair side by side.
const PREFERRED_PORT = 47711;

// Private ranges first (typical home Wi-Fi), then everything else — the QR
// needs exactly one address, so the most likely reachable one wins.
function lanAddresses() {
  const groups = [
    { re: /^192\.168\./, out: [] },
    { re: /^10\./, out: [] },
    { re: /^172\.(1[6-9]|2\d|3[01])\./, out: [] },
    { re: /./, out: [] },
  ];
  const ifaces = os.networkInterfaces();
  for (const name of Object.keys(ifaces)) {
    for (const info of ifaces[name] || []) {
      if (info.family !== 'IPv4' || info.internal) continue;
      const g = groups.find((x) => x.re.test(info.address));
      if (g) g.out.push(info.address);
    }
  }
  return groups.flatMap((g) => g.out);
}

function tokenOk(expected, got) {
  if (typeof got !== 'string' || got.length !== expected.length) return false;
  try {
    return crypto.timingSafeEqual(Buffer.from(expected), Buffer.from(got));
  } catch {
    return false;
  }
}

function pairingPayload({ host, port, token, name }) {
  return {
    indio: 'sync',
    v: PROTOCOL_VERSION,
    host,
    port,
    token,
    name: String(name || '').slice(0, 48),
  };
}

function readBody(req, limit = MAX_BODY_BYTES) {
  return new Promise((resolve, reject) => {
    let size = 0;
    const chunks = [];
    req.on('data', (c) => {
      size += c.length;
      if (size > limit) {
        reject(new Error('payload too large'));
        req.destroy();
        return;
      }
      chunks.push(c);
    });
    req.on('end', () => resolve(Buffer.concat(chunks).toString('utf8')));
    req.on('error', reject);
  });
}

function listen(server, port) {
  return new Promise((resolve, reject) => {
    const onError = (err) => {
      server.removeListener('listening', onOk);
      reject(err);
    };
    const onOk = () => {
      server.removeListener('error', onError);
      resolve(server.address().port);
    };
    server.once('error', onError);
    server.once('listening', onOk);
    server.listen(port, '0.0.0.0');
  });
}

// onRequest: async ({ kind: 'snapshot' | 'merge', payload? }) -> snapshot | { stats }
// onEvent:   (evt) => void for UI feedback ('linked' | 'pulled' | 'merged' | 'expired' | 'error')
async function startSyncServer({ onRequest, onEvent = () => {}, name = '' }) {
  if (typeof onRequest !== 'function') throw new Error('sync server needs an onRequest handler');
  const ips = lanAddresses();
  if (!ips.length) throw new Error('no LAN address — connect this PC to Wi-Fi or Ethernet first');
  const token = crypto.randomBytes(16).toString('hex');
  let linked = false;
  let closed = false;
  let idleTimer = null;

  const emit = (evt) => {
    try { onEvent(evt); } catch { /* UI listener died — server stays up */ }
  };

  const server = http.createServer(async (req, res) => {
    touch();
    const send = (code, obj) => {
      if (res.writableEnded) return;
      res.writeHead(code, { 'content-type': 'application/json; charset=utf-8', 'cache-control': 'no-store' });
      res.end(JSON.stringify(obj));
    };
    try {
      const url = new URL(req.url || '/', 'http://indiotube.local');
      if (req.method === 'GET' && url.pathname === '/indio/v1/ping') {
        return send(200, { app: 'indiotube', v: PROTOCOL_VERSION, name });
      }
      if (!tokenOk(token, url.searchParams.get('token'))) {
        return send(401, { error: 'pairing token missing or wrong' });
      }
      if (req.method === 'GET' && url.pathname === '/indio/v1/snapshot') {
        markLinked();
        const snapshot = await onRequest({ kind: 'snapshot' });
        emit({ type: 'pulled' });
        return send(200, { ok: true, snapshot });
      }
      if (req.method === 'POST' && url.pathname === '/indio/v1/merge') {
        const raw = await readBody(req);
        let payload = null;
        try { payload = JSON.parse(raw); } catch { return send(400, { error: 'body must be JSON' }); }
        if (!payload || typeof payload !== 'object' || Array.isArray(payload)) {
          return send(400, { error: 'body must be a JSON object' });
        }
        markLinked();
        const result = await onRequest({ kind: 'merge', payload });
        emit({ type: 'merged', stats: result?.stats || null, sender: payload.name || null });
        return send(200, { ok: true, stats: result?.stats || null });
      }
      return send(404, { error: 'unknown route' });
    } catch (err) {
      emit({ type: 'error', message: String(err?.message || err) });
      return send(err?.message === 'payload too large' ? 413 : 500, { error: String(err?.message || err) });
    }
  });

  const stop = () => {
    if (closed) return Promise.resolve();
    closed = true;
    if (idleTimer) clearTimeout(idleTimer);
    return new Promise((resolve) => {
      try { server.closeAllConnections?.(); } catch { /* older node */ }
      server.close(() => resolve());
      // close() alone never fires for keep-alive sockets; the call above plus
      // a short grace period is enough for a pairing server.
      setTimeout(resolve, 500);
    });
  };

  const touch = () => {
    if (closed) return;
    if (idleTimer) clearTimeout(idleTimer);
    idleTimer = setTimeout(() => {
      emit({ type: 'expired' });
      void stop();
    }, SESSION_TTL_MS);
    idleTimer.unref?.();
  };

  const markLinked = () => {
    if (linked) return;
    linked = true;
    emit({ type: 'linked', ips });
  };

  server.setTimeout(30 * 1000, (sock) => sock.destroy());

  let port;
  try {
    port = await listen(server, PREFERRED_PORT);
  } catch (err) {
    if (err && (err.code === 'EADDRINUSE' || err.code === 'EACCES')) {
      port = await listen(server, 0); // any free port — the QR carries it
    } else {
      throw err;
    }
  }
  touch();

  const host = ips[0];
  return {
    port,
    token,
    ips,
    host,
    stop,
    payload: pairingPayload({ host, port, token, name }),
  };
}

module.exports = {
  PROTOCOL_VERSION,
  startSyncServer,
  pairingPayload,
  lanAddresses,
  tokenOk,
};
