import express from 'express';
import { randomUUID, timingSafeEqual } from 'node:crypto';
import { pathToFileURL } from 'node:url';
import { Server } from '@modelcontextprotocol/sdk/server/index.js';
import { StreamableHTTPServerTransport } from '@modelcontextprotocol/sdk/server/streamableHttp.js';
import { CallToolRequestSchema, ListToolsRequestSchema } from '@modelcontextprotocol/sdk/types.js';
import { tools, validateCall } from './tools.js';

export function createRelay({ token = process.env.VAYU_TOKEN, commandTimeout = 30000, pollTimeout = 25000, staleMs = 60000 } = {}) {
  if (!token || token.length < 32) throw new Error('Set VAYU_TOKEN to a random token of at least 32 characters');
  const app = express();
  app.disable('x-powered-by');
  const devices = new Map();
  const pending = new Map();
  const expected = Buffer.from(`Bearer ${token}`);
  app.use((req, res, next) => {
    // No browser-origin calls; clients use Authorization, never URL tokens.
    if (req.headers.origin) return res.status(403).json({ error: 'Browser origins are not allowed' });
    const supplied = Buffer.from(req.headers.authorization || '');
    if (supplied.length !== expected.length || !timingSafeEqual(supplied, expected)) return res.status(401).json({ error: 'Unauthorized' });
    next();
  });
  app.use(express.json({ limit: '12mb' }));
  const online = d => Date.now() - d.lastSeen < staleMs;
  const validId = id => typeof id === 'string' && /^[a-zA-Z0-9._-]{1,128}$/.test(id);
  app.get('/api/health', (_req, res) => res.json({ status: 'ok', version: '11.0.0', mode: 'direct-control' }));
  app.post('/api/register', (req, res) => {
    const { deviceId, model, androidVersion } = req.body;
    if (!validId(deviceId)) return res.status(400).json({ error: 'Invalid deviceId' });
    if (!devices.has(deviceId) && devices.size >= 16) return res.status(429).json({ error: 'Device limit reached' });
    const device = devices.get(deviceId) || { deviceId, queue: [], poll: null };
    Object.assign(device, { model: String(model || '').slice(0, 120), androidVersion: String(androidVersion || '').slice(0, 32), lastSeen: Date.now() });
    devices.set(deviceId, device);
    res.json({ success: true });
  });
  app.get('/api/devices', (_req, res) => res.json({ devices: listDevices() }));
  function listDevices() { return [...devices.values()].map(({ deviceId, model, androidVersion, lastSeen }) => ({ deviceId, model, androidVersion, connected: Date.now() - lastSeen < staleMs })); }
  app.get('/api/poll', (req, res) => {
    const device = devices.get(req.query.deviceId);
    if (!device) return res.status(404).json({ error: 'Register first' });
    device.lastSeen = Date.now();
    if (device.poll) return res.status(409).json({ error: 'A poll is already active' });
    function deliver() {
      const commands = device.queue.splice(0).filter(c => pending.has(c.requestId));
      res.json({ commands });
    }
    if (device.queue.length) return deliver();
    let timer;
    const wake = () => { clearTimeout(timer); device.poll = null; if (!res.destroyed) deliver(); };
    device.poll = wake;
    timer = setTimeout(wake, pollTimeout);
    res.on('close', () => { clearTimeout(timer); if (device.poll === wake) device.poll = null; });
  });
  app.post('/api/response', (req, res) => {
    const item = pending.get(req.body.requestId);
    if (!item || req.body.deviceId !== item.device.deviceId) return res.status(404).json({ error: 'No matching pending command' });
    item.device.lastSeen = Date.now();
    item.finish(req.body);
    res.json({ success: true });
  });
  async function dispatch(name, args) {
    const command = validateCall(name, args);
    if (name === 'vayu_devices') return { devices: listDevices() };
    const candidates = [...devices.values()].filter(online);
    const device = command.deviceId ? devices.get(command.deviceId) : candidates.length === 1 ? candidates[0] : null;
    if (!device || !online(device)) throw new Error(candidates.length > 1 ? 'Select deviceId; multiple phones are online' : 'No selected phone online. Enable accessibility and connect in V.A.Y.U.');
    // One pending command per phone. Never replay a timed-out action.
    if ([...pending.values()].some(p => p.device === device)) throw new Error('Phone is busy; observe again after the current command finishes');
    const requestId = randomUUID();
    return new Promise(resolve => {
      const finish = result => {
        clearTimeout(timer);
        pending.delete(requestId);
        device.queue = device.queue.filter(c => c.requestId !== requestId);
        resolve(result);
      };
      const timer = setTimeout(() => finish({ success: false, message: 'Phone response timed out. Action may have run; observe before retrying.' }), commandTimeout);
      pending.set(requestId, { device, finish });
      device.queue.push({ ...command, requestId });
      device.poll?.();
    });
  }
  function format(result) {
    const { base64, ...metadata } = result;
    const content = [];
    if (base64) content.push({ type: 'image', data: base64, mimeType: result.mimeType || 'image/jpeg' });
    content.push({ type: 'text', text: JSON.stringify(metadata) });
    return { content, ...(result.success === false ? { isError: true } : {}) };
  }
  // Useful for local smoke tests and stdio clients. Same validation/auth as MCP.
  app.post('/api/call', async (req, res) => {
    try { res.json(format(await dispatch(req.body.name, req.body.arguments || {}))); }
    catch (e) { res.status(400).json({ error: e.message }); }
  });
  app.post('/mcp', async (req, res) => {
    // Stateless SDK transport: clients do not lose a session when a proxy drops a header.
    const server = new Server({ name: 'vayu-direct-controller', version: '11.0.0' }, { capabilities: { tools: {} } });
    server.setRequestHandler(ListToolsRequestSchema, async () => ({ tools }));
    server.setRequestHandler(CallToolRequestSchema, async request => {
      try { return format(await dispatch(request.params.name, request.params.arguments || {})); }
      catch (e) { return { isError: true, content: [{ type: 'text', text: e.message }] }; }
    });
    const transport = new StreamableHTTPServerTransport({ sessionIdGenerator: undefined, enableJsonResponse: true });
    res.on('close', () => { void transport.close(); void server.close(); });
    try {
      await server.connect(transport);
      await transport.handleRequest(req, res, req.body);
    } catch (e) {
      if (!res.headersSent) res.status(500).json({ error: 'MCP request failed' });
    }
  });
  app.all('/mcp', (_req, res) => res.status(405).json({ error: 'Use Streamable HTTP POST' }));
  app.use((err, _req, res, _next) => res.status(err.status || 400).json({ error: 'Invalid request' }));
  function close() {
    for (const item of pending.values()) item.finish({ success: false, message: 'Relay stopped' });
    for (const device of devices.values()) device.poll?.();
  }
  return { app, close };
}
export function startRelay() {
  const relay = createRelay();
  const port = Number(process.env.PORT || 8787);
  const host = process.env.HOST || '127.0.0.1';
  const listener = relay.app.listen(port, host, () => console.log(`V.A.Y.U direct-control relay: http://${host}:${port}/mcp`));
  for (const signal of ['SIGINT', 'SIGTERM']) process.once(signal, () => { relay.close(); listener.close(); });
  return listener;
}
if (process.argv[1] && import.meta.url === pathToFileURL(process.argv[1]).href) startRelay();
