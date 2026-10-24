#!/usr/bin/env node
// Optional stdio adapter. The Termux relay itself provides remote HTTP MCP.
const { Server } = require('@modelcontextprotocol/sdk/server/index.js');
const { StdioServerTransport } = require('@modelcontextprotocol/sdk/server/stdio.js');
const { CallToolRequestSchema, ListToolsRequestSchema } = require('@modelcontextprotocol/sdk/types.js');
async function main() {
  const { tools } = await import('../vayu-relay/tools.js');
  const relay = process.env.RELAY_URL || 'http://127.0.0.1:8787';
  if (!process.env.VAYU_TOKEN) throw new Error('VAYU_TOKEN is required');
  const server = new Server({ name: 'vayu-direct-controller', version: '11.0.0' }, { capabilities: { tools: {} } });
  server.setRequestHandler(ListToolsRequestSchema, async () => ({ tools }));
  server.setRequestHandler(CallToolRequestSchema, async req => {
    try {
      const response = await fetch(`${relay}/api/call`, { method: 'POST',
        headers: { Authorization: `Bearer ${process.env.VAYU_TOKEN}`, 'Content-Type': 'application/json' },
        body: JSON.stringify(req.params), signal: AbortSignal.timeout(35000) });
      if (!response.ok) throw new Error(`Relay HTTP ${response.status}`);
      return await response.json();
    } catch (e) { return { isError: true, content: [{ type: 'text', text: e.message }] }; }
  });
  await server.connect(new StdioServerTransport());
}
main().catch(e => { console.error(e.message); process.exitCode = 1; });
