/**
 * LUI Bridge — Connect AI agents to Android devices.
 *
 * Usage:
 *   const { LuiBridge } = require('lui-bridge');
 *
 *   const bridge = new LuiBridge('ws://PHONE_IP:8765', 'YOUR_TOKEN');
 *   await bridge.connect();
 *   console.log(await bridge.callTool('battery'));
 *   bridge.disconnect();
 */

const WebSocket = require('ws');

class LuiBridge {
  /**
   * @param {string} url - WebSocket URL (e.g., ws://PHONE_IP:8765)
   * @param {string} token - Bridge auth token
   * @param {Object} [options]
   * @param {string} [options.agentName] - Register as named agent for bidirectional comms
   * @param {Function} [options.onInstruction] - async (instruction) => response string
   * @param {Function} [options.onEvent] - (eventType, data) => void
   */
  constructor(url, token, options = {}) {
    this.url = url;
    this.token = token;
    this.agentName = options.agentName || null;
    this.onInstruction = options.onInstruction || null;
    this.onEvent = options.onEvent || null;
    this.ws = null;
    this.connected = false;
    this.tools = [];
    this._requestId = 0;
    this._pending = new Map();
    this._pingInterval = null;
  }

  /**
   * Connect, authenticate, initialize MCP, optionally register as agent.
   * @returns {Promise<number>} Number of available tools
   */
  async connect() {
    // Reset state in case this instance is being reconnected.
    this._pending.clear();
    this._listening = false;
    if (this._pingInterval) { clearInterval(this._pingInterval); this._pingInterval = null; }

    return new Promise((resolve, reject) => {
      this.ws = new WebSocket(this.url);

      this.ws.on('error', (err) => {
        if (!this.connected) reject(err);
      });

      this.ws.on('open', async () => {
        try {
          const isRelay = this.url.includes('/agent') || this.url.includes('/device');

          // Relay auth: send token as first message
          if (isRelay) {
            this._send({ type: 'auth', device_token: this.token });
            const relayAuth = await this._readOne();
            if (relayAuth?.type !== 'auth' || relayAuth?.status !== 'ok') {
              throw new Error(`Relay auth failed: ${JSON.stringify(relayAuth)}`);
            }
          }

          // Device auth (direct or through relay)
          this._send({ method: 'auth', params: { token: this.token } });
          const auth = await this._readOne();
          if (!auth?.result?.authenticated) {
            throw new Error(`Auth failed: ${JSON.stringify(auth)}`);
          }

          // Initialize MCP
          await this._call('initialize', {
            protocolVersion: '2025-03-26',
            clientInfo: { name: this.agentName || 'lui-node', version: '0.1.0' }
          });

          // Register as agent
          if (this.agentName) {
            this._send({
              jsonrpc: '2.0', id: this._nextId(),
              method: 'lui/register',
              params: {
                name: this.agentName,
                description: `${this.agentName} agent`,
                capabilities: []
              }
            });
            await this._drain();
          }

          // Get tools
          const toolsResp = await this._call('tools/list');
          this.tools = toolsResp?.tools || [];

          this.connected = true;
          this._startListener();
          this._startKeepalive();
          resolve(this.tools.length);
        } catch (err) {
          reject(err);
        }
      });

      this.ws.on('close', () => {
        this.connected = false;
        if (this._pingInterval) { clearInterval(this._pingInterval); this._pingInterval = null; }
      });
    });
  }

  /** Disconnect from the bridge. */
  disconnect() {
    this.connected = false;
    if (this._pingInterval) { clearInterval(this._pingInterval); this._pingInterval = null; }
    if (this.ws) {
      this.ws.close();
      this.ws = null;
    }
  }

  /**
   * Send a WebSocket ping every 25s so idle proxies (e.g. Fly edge) don't
   * close the connection. The 'ws' library auto-replies to pings server-side
   * but doesn't auto-send from the client.
   */
  _startKeepalive() {
    this._pingInterval = setInterval(() => {
      if (this.ws && this.ws.readyState === WebSocket.OPEN) {
        try { this.ws.ping(); } catch {}
      }
    }, 25000);
  }

  /**
   * Call a LUI tool.
   * @param {string} name - Tool name
   * @param {Object} [args] - Tool arguments
   * @returns {Promise<string>} Result text
   */
  async callTool(name, args = {}) {
    const resp = await this._call('tools/call', { name, arguments: args });
    const content = resp?.content || [];
    if (content.length > 0) {
      const text = content[0].text || '';
      if (resp.isError) throw new ToolError(text);
      return text;
    }
    return '';
  }

  /**
   * Get current device state.
   * @returns {Promise<string>}
   */
  async getDeviceState() {
    const resp = await this._call('resources/read', { uri: 'lui://device/state' });
    const contents = resp?.contents || [];
    return contents.length > 0 ? contents[0].text || '' : '';
  }

  /** @returns {string[]} Available tool names */
  listTools() {
    return this.tools.map(t => t.name);
  }

  /** @returns {Object[]} Full tool definitions */
  listToolsDetailed() {
    return this.tools;
  }

  /** @returns {Promise<boolean>} */
  async ping() {
    try {
      await this._call('ping');
      return true;
    } catch {
      return false;
    }
  }

  // ── Internal ──

  _nextId() {
    return String(++this._requestId);
  }

  _send(msg) {
    this.ws.send(JSON.stringify(msg));
  }

  _readOne() {
    return new Promise((resolve, reject) => {
      const timeout = setTimeout(() => reject(new Error('Timeout')), 10000);
      this.ws.once('message', (data) => {
        clearTimeout(timeout);
        resolve(JSON.parse(data.toString()));
      });
    });
  }

  async _drain() {
    await new Promise(r => setTimeout(r, 500));
    // Read and discard buffered messages
    return new Promise((resolve) => {
      const handler = () => {};
      this.ws.on('message', handler);
      setTimeout(() => {
        this.ws.removeListener('message', handler);
        resolve();
      }, 300);
    });
  }

  _call(method, params) {
    return new Promise((resolve, reject) => {
      const id = this._nextId();
      const msg = { jsonrpc: '2.0', id, method };
      if (params) msg.params = params;

      const timeout = setTimeout(() => {
        this._pending.delete(id);
        reject(new Error(`Timeout waiting for ${method}`));
      }, 30000);

      this._pending.set(id, { resolve, reject, timeout });
      this._send(msg);

      // If listener not running, read directly
      if (!this._listening) {
        const directHandler = (data) => {
          const resp = JSON.parse(data.toString());
          if (String(resp.id) === id) {
            this.ws.removeListener('message', directHandler);
            clearTimeout(timeout);
            this._pending.delete(id);
            resolve(resp.result || {});
          }
        };
        this.ws.on('message', directHandler);
      }
    });
  }

  _startListener() {
    this._listening = true;
    this.ws.on('message', (data) => {
      try {
        const msg = JSON.parse(data.toString());
        const id = String(msg.id || '');

        // Pending response
        if (this._pending.has(id)) {
          const p = this._pending.get(id);
          clearTimeout(p.timeout);
          this._pending.delete(id);
          p.resolve(msg.result || {});
          return;
        }

        // Instruction from user
        if (msg.method === 'lui/instruction' && this.onInstruction) {
          const instruction = msg.params?.instruction || '';
          const instrId = msg.id || '';

          Promise.resolve(this.onInstruction(instruction)).then((response) => {
            this._send({
              jsonrpc: '2.0', id: 'resp',
              method: 'lui/response',
              params: { instruction_id: String(instrId), result: String(response).slice(0, 1000) }
            });
          }).catch(() => {});
        }

        // Event
        if (msg.method === 'notifications/lui/event' && this.onEvent) {
          const event = msg.params || {};
          this.onEvent(event.type, event.data || {});
        }
      } catch {}
    });
  }
}

class ToolError extends Error {
  constructor(message) {
    super(message);
    this.name = 'ToolError';
  }
}

module.exports = { LuiBridge, ToolError };
