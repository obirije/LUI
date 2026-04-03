#!/usr/bin/env node

/**
 * LUI CLI — Connect agents to LUI devices.
 *
 * lui bridge connect --url ws://PHONE_IP:8765 --token TOKEN --agent NAME --mode MODE
 * lui bridge tools   --url ws://PHONE_IP:8765 --token TOKEN
 * lui bridge call    --url ws://PHONE_IP:8765 --token TOKEN --tool TOOL [--args JSON]
 * lui bridge status  --url ws://PHONE_IP:8765 --token TOKEN
 * lui relay start    [--port PORT]
 */

const { LuiBridge } = require('./index');
const { execSync, spawn } = require('child_process');

const args = process.argv.slice(2);

function getArg(name, fallback = null) {
  const idx = args.indexOf(`--${name}`);
  if (idx >= 0 && idx + 1 < args.length) return args[idx + 1];
  return fallback;
}

function hasArg(name) {
  return args.includes(`--${name}`);
}

const command = args[0];
const subcommand = args[1];

async function main() {
  if (hasArg('version')) {
    console.log('lui-bridge 0.1.0');
    return;
  }

  if (hasArg('help') || !command) {
    console.log(`
lui — Android agent bridge and relay

Commands:
  lui bridge connect  --url URL --token TOKEN [--agent NAME] [--mode MODE]
  lui bridge tools    --url URL --token TOKEN
  lui bridge call     --url URL --token TOKEN --tool TOOL [--args JSON]
  lui bridge status   --url URL --token TOKEN
  lui relay start     [--port PORT]

Options:
  --url     Bridge WebSocket URL (ws://PHONE_IP:8765)
  --token   Auth token (from LUI notification)
  --agent   Agent name (for connect mode)
  --mode    Execution mode: echo, shell, hermes, claude-code (default: echo)
  --tool    Tool name (for call mode)
  --args    Tool arguments as JSON (for call mode)
  --port    Relay server port (default: 9000)
  --help    Show this help
  --version Show version
`);
    return;
  }

  if (command === 'bridge') {
    const url = getArg('url');
    const token = getArg('token');

    if (!url || !token) {
      console.error('Required: --url and --token');
      process.exit(1);
    }

    if (subcommand === 'connect') {
      await bridgeConnect(url, token);
    } else if (subcommand === 'tools') {
      await bridgeTools(url, token);
    } else if (subcommand === 'call') {
      await bridgeCall(url, token);
    } else if (subcommand === 'status') {
      await bridgeStatus(url, token);
    } else {
      console.error(`Unknown bridge command: ${subcommand}`);
      process.exit(1);
    }
  } else if (command === 'relay') {
    if (subcommand === 'start') {
      await relayStart();
    } else {
      console.error(`Unknown relay command: ${subcommand}`);
      process.exit(1);
    }
  } else {
    console.error(`Unknown command: ${command}. Use --help`);
    process.exit(1);
  }
}

async function bridgeConnect(url, token) {
  const agentName = getArg('agent', 'node-agent');
  const mode = getArg('mode', 'echo');
  const executor = getExecutor(mode);

  const bridge = new LuiBridge(url, token, {
    agentName,
    onInstruction: async (instruction) => {
      console.log(`\n[→ ${agentName}] ${instruction}`);
      const result = await executor(instruction);
      console.log(`[← ${agentName}] ${result.slice(0, 120)}`);
      return result;
    },
    onEvent: (type, data) => {
      if (type === 'notification_2fa') {
        console.log(`\n[2FA] Code: ${data.code} from ${data.app}`);
      } else if (type === 'call_incoming' || type === 'call_missed') {
        console.log(`\n[${type.toUpperCase()}] ${data.caller || '?'}`);
      } else if (type === 'notification') {
        console.log(`\n[NOTIF] ${data.title || ''}: ${(data.text || '').slice(0, 50)}`);
      }
    }
  });

  const toolCount = await bridge.connect();
  const state = await bridge.getDeviceState();
  console.log(`Connected to LUI as '${agentName}' — ${toolCount} tools, mode=${mode}`);
  console.log(`Device: ${state.split('\n')[0]}`);
  console.log(`\nOn LUI say: 'patch me to ${agentName}' or '@${agentName} do something'`);
  console.log('Listening... (Ctrl+C to exit)\n');

  process.on('SIGINT', () => {
    bridge.disconnect();
    console.log('\nDisconnected.');
    process.exit(0);
  });

  // Keep alive
  await new Promise(() => {});
}

async function bridgeTools(url, token) {
  const bridge = new LuiBridge(url, token);
  const count = await bridge.connect();
  const tools = bridge.listToolsDetailed();
  bridge.disconnect();

  console.log(`${count} tools available:\n`);
  for (const t of tools) {
    const params = Object.keys(t.inputSchema?.properties || {}).join(', ');
    console.log(`  ${t.name}(${params})`);
    console.log(`    ${t.description}\n`);
  }
}

async function bridgeCall(url, token) {
  const tool = getArg('tool');
  if (!tool) {
    console.error('Required: --tool');
    process.exit(1);
  }

  const argsJson = getArg('args', '{}');
  let toolArgs;
  try {
    toolArgs = JSON.parse(argsJson);
  } catch {
    console.error('Invalid --args JSON');
    process.exit(1);
  }

  const bridge = new LuiBridge(url, token);
  await bridge.connect();
  const result = await bridge.callTool(tool, toolArgs);
  bridge.disconnect();
  console.log(result);
}

async function bridgeStatus(url, token) {
  const bridge = new LuiBridge(url, token);
  try {
    const count = await bridge.connect();
    const state = await bridge.getDeviceState();
    bridge.disconnect();
    console.log(`Connected — ${count} tools\n`);
    console.log(state);
  } catch (err) {
    console.error(`Failed: ${err.message}`);
    process.exit(1);
  }
}

async function relayStart() {
  const port = getArg('port', '9000');
  console.log(`Starting LUI relay on port ${port}...`);
  console.log('Note: The relay server requires Python. Use: lui relay start (Python CLI)');
  console.log(`Or: python3 relay/relay_server.py (PORT=${port})`);
  process.exit(1);
}

function getExecutor(mode) {
  switch (mode) {
    case 'shell':
      return (instruction) => {
        try {
          const output = execSync(instruction, { timeout: 30000, encoding: 'utf-8', cwd: process.env.HOME });
          return output.trim() || 'Done.';
        } catch (err) {
          return err.stderr?.trim() || err.message || 'Command failed.';
        }
      };
    case 'hermes':
      return (instruction) => {
        try {
          const output = execSync(`hermes chat -q "${instruction.replace(/"/g, '\\"')}" --yolo`, {
            timeout: 120000, encoding: 'utf-8', env: { ...process.env, TERM: 'dumb', NO_COLOR: '1' }
          });
          // Strip ANSI and box drawing
          const clean = output.replace(/\x1b\[[0-9;]*[a-zA-Z]/g, '')
            .replace(/[╭╮╰╯│─┃━┏┓┗┛┡┩┣┫╌╍⠋⠙⠹⠸⠼⠴⠦⠧⠇⠏]/g, '')
            .split('\n')
            .map(l => l.trim())
            .filter(l => l && !l.startsWith('Hermes Agent') && !l.includes('──────'))
            .slice(-15)
            .join('\n');
          return clean || 'Hermes returned no output.';
        } catch (err) {
          return `Hermes error: ${err.message}`;
        }
      };
    case 'claude-code':
      return (instruction) => {
        try {
          const output = execSync(`claude --print --dangerously-skip-permissions "${instruction.replace(/"/g, '\\"')}"`, {
            timeout: 120000, encoding: 'utf-8'
          });
          return output.replace(/\x1b\[[0-9;]*m/g, '').trim().slice(0, 1000) || 'Claude Code returned no output.';
        } catch (err) {
          return `Claude Code error: ${err.message}`;
        }
      };
    default:
      return (instruction) => `Received: ${instruction}`;
  }
}

main().catch(err => {
  console.error(err.message);
  process.exit(1);
});
