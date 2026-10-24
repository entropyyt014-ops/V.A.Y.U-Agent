import test from 'node:test';
import assert from 'node:assert/strict';
import { createRelay } from '../vayu-relay/server.js';
import { Client } from '@modelcontextprotocol/sdk/client/index.js';
import { StreamableHTTPClientTransport } from '@modelcontextprotocol/sdk/client/streamableHttp.js';
const token = 'test-only-token-00000000000000000000000000';
async function fixture(t, options = {}) {
  const relay = createRelay({ token, commandTimeout: 150, pollTimeout: 40, ...options });
  const server = relay.app.listen(0, '127.0.0.1');
  await new Promise(r => server.once('listening', r));
  const url = `http://127.0.0.1:${server.address().port}`;
  t.after(() => { relay.close(); server.closeAllConnections(); server.close(); });
  const call = (path, body, headers = {}) => fetch(url + path, { method: body ? 'POST' : 'GET', headers: { Authorization: `Bearer ${token}`, 'Content-Type': 'application/json', ...headers }, ...(body ? { body: JSON.stringify(body) } : {}) });
  const register = async (id = 'phone') => { assert.equal((await call('/api/register', { deviceId: id })).status, 200); };
  return { call, register, url };
}
test('requires a strong configured token, rejects unauthenticated requests and browser origins', async t => {
  assert.throws(() => createRelay({ token: '' }));
  const { call } = await fixture(t);
  assert.equal((await call('/api/health', null, { Authorization: '' })).status, 401);
  assert.equal((await call('/api/health', null, { Origin: 'https://evil.example' })).status, 403);
  assert.equal((await call('/api/health')).status, 200);
});
test('direct command round trip correlates response to the correct device', async t => {
  const { call, register } = await fixture(t, { commandTimeout: 2000 });
  await register();
  const result = call('/api/call', { name: 'vayu_tap', arguments: { x: 250, y: 900 } });
  const poll = await (await call('/api/poll?deviceId=phone')).json();
  const command = poll.commands[0];
  assert.equal(command.type, 'tap'); assert.equal(command.y, 900);
  assert.equal((await call('/api/response', { requestId: command.requestId, deviceId: 'other', success: true })).status, 404);
  await call('/api/response', { requestId: command.requestId, deviceId: 'phone', success: false, message: 'Tap cancelled' });
  assert.equal((await (await result).json()).isError, true);
  assert.equal((await call('/api/response', { requestId: command.requestId, deviceId: 'phone' })).status, 404);
});
test('expired queued actions are removed rather than replayed on reconnect', async t => {
  const { call, register } = await fixture(t);
  await register();
  const result = await (await call('/api/call', { name: 'vayu_press_home' })).json();
  assert.equal(result.isError, true);
  const poll = await (await call('/api/poll?deviceId=phone')).json();
  assert.deepEqual(poll.commands, []);
});
test('rejects unknown tools, model delegation, invalid coordinates and ambiguous devices', async t => {
  const { call, register } = await fixture(t);
  await register('one'); await register('two');
  for (const payload of [
    { name: 'vayu_execute', arguments: { task: 'do anything' } },
    { name: 'vayu_tap', arguments: { x: -1, y: 20 } },
    { name: 'vayu_tap', arguments: { x: 20 } },
    { name: 'vayu_open_url', arguments: { url: 'intent://anything' } },
    { name: 'vayu_look' },
  ]) assert.equal((await call('/api/call', payload)).status, 400);
});
test('serializes commands per device and rejects a second active poll', async t => {
  const { call, register } = await fixture(t, { commandTimeout: 1500, pollTimeout: 300 });
  await register();
  const waitingPoll = call('/api/poll?deviceId=phone');
  // Health request ensures the first poll has reached the server.
  await call('/api/health');
  assert.equal((await call('/api/poll?deviceId=phone')).status, 409);
  const first = call('/api/call', { name: 'vayu_press_back' });
  const { commands } = await (await waitingPoll).json();
  assert.equal((await call('/api/call', { name: 'vayu_press_home' })).status, 400);
  await call('/api/response', { deviceId: 'phone', requestId: commands[0].requestId, success: true });
  assert.equal((await (await first).json()).isError, undefined);
});
test('official MCP client initializes, lists and calls tools without session state', async t => {
  const { url } = await fixture(t);
  const client = new Client({ name: 'test', version: '1' });
  const transport = new StreamableHTTPClientTransport(new URL(`${url}/mcp`), { requestInit: { headers: { Authorization: `Bearer ${token}` } } });
  t.after(() => client.close());
  await client.connect(transport);
  const { tools } = await client.listTools();
  assert.ok(tools.some(x => x.name === 'vayu_look'));
  assert.ok(!tools.some(x => /execute|create_image|model/.test(x.name)));
  const result = await client.callTool({ name: 'vayu_devices', arguments: {} });
  assert.deepEqual(JSON.parse(result.content[0].text), { devices: [] });
});
