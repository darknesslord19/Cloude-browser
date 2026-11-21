'use strict';
// Cloude Browser aracı sunucusu.
// Claude  --MCP (HTTP POST /mcp/<TOKEN>)-->  bu sunucu  <--uzun yoklama--  Android uygulaması
// Bağımlılık yok, yalnızca Node 18+.

const http = require('http');
const crypto = require('crypto');

const PORT = process.env.PORT || 8080;
let TOKEN = process.env.TOKEN;
if (!TOKEN) {
  TOKEN = crypto.randomBytes(16).toString('hex');
  console.log('TOKEN ortam değişkeni yok, geçici üretildi:', TOKEN);
}

const queue = [];            // cihaza gidecek komutlar
const pending = new Map();   // id -> {resolve, reject, timer}
let waiter = null;           // bekleyen poll isteği
let lastSeen = 0;            // cihazın son görülme zamanı

function same(a, b) {
  const x = Buffer.from(String(a)), y = Buffer.from(String(b));
  return x.length === y.length && crypto.timingSafeEqual(x, y);
}

function send(res, code, obj, headers) {
  const body = obj === undefined ? '' : JSON.stringify(obj);
  res.writeHead(code, Object.assign({ 'Content-Type': 'application/json' }, headers || {}));
  res.end(body);
}

function readBody(req, limit) {
  return new Promise((resolve, reject) => {
    let size = 0; const chunks = [];
    req.on('data', c => {
      size += c.length;
      if (size > limit) { reject(new Error('Gövde çok büyük')); req.destroy(); return; }
      chunks.push(c);
    });
    req.on('end', () => resolve(Buffer.concat(chunks).toString('utf8')));
    req.on('error', reject);
  });
}

function dispatchToWaiter() {
  if (waiter && queue.length) {
    const w = waiter; waiter = null; clearTimeout(w.timer);
    send(w.res, 200, queue.shift());
  }
}

function callDevice(action, args, timeoutMs) {
  return new Promise((resolve, reject) => {
    if (Date.now() - lastSeen > 75000) {
      reject(new Error('Telefondaki uygulama bağlı değil. Uygulamayı açık tut ve ayarları kontrol et.'));
      return;
    }
    const id = crypto.randomBytes(6).toString('hex');
    const timer = setTimeout(() => {
      pending.delete(id);
      reject(new Error('Cihaz zamanında yanıt vermedi.'));
    }, timeoutMs || 40000);
    pending.set(id, { resolve, reject, timer });
    queue.push({ id, action, args: args || {} });
    dispatchToWaiter();
  });
}

const TOOLS = [
  { name: 'browser_navigate', description: 'Telefondaki tarayıcıda bir http/https adresi açar ve sayfa yüklenince başlığı ile adresi döndürür.',
    inputSchema: { type: 'object', properties: { url: { type: 'string' } }, required: ['url'] } },
  { name: 'browser_info', description: 'Açık sayfanın adresini ve başlığını döndürür.',
    inputSchema: { type: 'object', properties: {} } },
  { name: 'browser_get_text', description: 'Açık sayfanın görünen metnini döndürür.',
    inputSchema: { type: 'object', properties: { max: { type: 'number', description: 'En fazla karakter (varsayılan 20000)' } } } },
  { name: 'browser_get_links', description: 'Sayfadaki bağlantıları (metin ve adres) listeler.',
    inputSchema: { type: 'object', properties: { max: { type: 'number' } } } },
  { name: 'browser_report', description: 'Sayfa için güvenlik odaklı özet çıkarır: formlar, şifre alanı, dış betik ve iframe adresleri, indirme bağlantıları, dış bağlantı alan adları.',
    inputSchema: { type: 'object', properties: {} } },
  { name: 'browser_click', description: 'Bir öğeye tıklar. CSS seçici ya da görünen metin ver.',
    inputSchema: { type: 'object', properties: { selector: { type: 'string' }, text: { type: 'string' } } } },
  { name: 'browser_type', description: 'Bir alana metin yazar. submit true ise formu gönderir.',
    inputSchema: { type: 'object', properties: { selector: { type: 'string' }, text: { type: 'string' }, submit: { type: 'boolean' } }, required: ['selector', 'text'] } },
  { name: 'browser_scroll', description: 'Sayfayı dikey kaydırır (piksel, negatif yukarı).',
    inputSchema: { type: 'object', properties: { dy: { type: 'number' } }, required: ['dy'] } },
  { name: 'browser_back', description: 'Bir önceki sayfaya döner.',
    inputSchema: { type: 'object', properties: {} } },
  { name: 'browser_screenshot', description: 'Tarayıcı görünümünün ekran görüntüsünü alır.',
    inputSchema: { type: 'object', properties: {} } },
  { name: 'browser_eval', description: 'Sayfada JavaScript çalıştırır. Kod bir fonksiyon gövdesidir, sonucu return ile döndür.',
    inputSchema: { type: 'object', properties: { script: { type: 'string' } }, required: ['script'] } }
];

const ACTION = {
  browser_navigate: 'navigate', browser_info: 'info', browser_get_text: 'text', browser_get_links: 'links',
  browser_report: 'report', browser_click: 'click', browser_type: 'type', browser_scroll: 'scroll',
  browser_back: 'back', browser_screenshot: 'screenshot', browser_eval: 'eval'
};

async function callTool(name, args) {
  const action = ACTION[name];
  if (!action) throw new Error('Bilinmeyen araç: ' + name);
  const r = await callDevice(action, args || {});
  if (!r.ok) return { content: [{ type: 'text', text: 'Hata: ' + (r.error || 'bilinmiyor') }], isError: true };
  if (action === 'screenshot') return { content: [{ type: 'image', data: r.data, mimeType: 'image/jpeg' }] };
  return { content: [{ type: 'text', text: typeof r.data === 'string' ? r.data : JSON.stringify(r.data) }] };
}

async function handleRpc(m) {
  const id = m.id;
  const ok = result => ({ jsonrpc: '2.0', id, result });
  const fail = (code, message) => ({ jsonrpc: '2.0', id, error: { code, message } });
  if (id === undefined) return null; // bildirim
  try {
    switch (m.method) {
      case 'initialize':
        return ok({ protocolVersion: (m.params && m.params.protocolVersion) || '2025-03-26',
          capabilities: { tools: {} }, serverInfo: { name: 'cloude-browser', version: '1.0.0' } });
      case 'ping': return ok({});
      case 'tools/list': return ok({ tools: TOOLS });
      case 'tools/call': return ok(await callTool(m.params && m.params.name, m.params && m.params.arguments));
      default: return fail(-32601, 'Yöntem yok: ' + m.method);
    }
  } catch (e) {
    return ok({ content: [{ type: 'text', text: 'Hata: ' + e.message }], isError: true });
  }
}

const server = http.createServer(async (req, res) => {
  const url = new URL(req.url, 'http://x');
  const parts = url.pathname.split('/').filter(Boolean);
  try {
    if (url.pathname === '/healthz') return send(res, 200, { ok: true, deviceConnected: Date.now() - lastSeen < 75000 });
    if (parts.length === 0) { res.writeHead(200, { 'Content-Type': 'text/plain; charset=utf-8' }); return res.end('Cloude Browser aracı sunucusu çalışıyor.'); }

    // MCP
    if (parts[0] === 'mcp' && parts.length === 2) {
      if (!same(parts[1], TOKEN)) return send(res, 404, { error: 'yok' });
      if (req.method !== 'POST') return send(res, 405, { error: 'Yalnızca POST' }, { Allow: 'POST' });
      const raw = await readBody(req, 1 << 20);
      let msg; try { msg = JSON.parse(raw); } catch (e) { return send(res, 400, { jsonrpc: '2.0', id: null, error: { code: -32700, message: 'Geçersiz JSON' } }); }
      if (Array.isArray(msg)) {
        const out = (await Promise.all(msg.map(handleRpc))).filter(Boolean);
        return out.length ? send(res, 200, out) : send(res, 202);
      }
      const out = await handleRpc(msg);
      return out ? send(res, 200, out) : send(res, 202);
    }

    // Cihaz
    if (parts[0] === 'device' && parts.length === 3) {
      if (!same(parts[1], TOKEN)) return send(res, 404, { error: 'yok' });
      if (parts[2] === 'poll' && req.method === 'GET') {
        lastSeen = Date.now();
        if (waiter) { const old = waiter; waiter = null; clearTimeout(old.timer); send(old.res, 204); }
        if (queue.length) return send(res, 200, queue.shift());
        const timer = setTimeout(() => { if (waiter && waiter.res === res) waiter = null; lastSeen = Date.now(); res.writeHead(204); res.end(); }, 25000);
        waiter = { res, timer };
        req.on('close', () => { if (waiter && waiter.res === res) { clearTimeout(timer); waiter = null; } });
        return;
      }
      if (parts[2] === 'result' && req.method === 'POST') {
        lastSeen = Date.now();
        const body = JSON.parse(await readBody(req, 8 << 20));
        const p = pending.get(body.id);
        if (p) { clearTimeout(p.timer); pending.delete(body.id); p.resolve(body); }
        return send(res, 200, { ok: true });
      }
    }
    send(res, 404, { error: 'yok' });
  } catch (e) {
    if (!res.headersSent) send(res, 500, { error: e.message });
  }
});

server.listen(PORT, () => console.log('Dinleniyor: ' + PORT));
