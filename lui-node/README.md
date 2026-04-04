# lui-bridge

Connect AI agents to LUI Android devices. 78 phone tools, MCP protocol, bidirectional communication.

## Install

```bash
npm install -g lui-bridge
```

## CLI

```bash
# Connect as an agent
lui bridge connect --url ws://PHONE_IP:8765 --token TOKEN --agent claude-code --mode claude-code
lui bridge connect --url ws://PHONE_IP:8765 --token TOKEN --agent hermes --mode hermes

# List tools
lui bridge tools --url ws://PHONE_IP:8765 --token TOKEN

# Call a single tool
lui bridge call --url ws://PHONE_IP:8765 --token TOKEN --tool battery
lui bridge call --url ws://PHONE_IP:8765 --token TOKEN --tool toggle_flashlight --args '{"state":"on"}'

# Check device
lui bridge status --url ws://PHONE_IP:8765 --token TOKEN
```

## JavaScript API

```javascript
const { LuiBridge } = require('lui-bridge');

const bridge = new LuiBridge('ws://PHONE_IP:8765', 'YOUR_TOKEN');
await bridge.connect();

// Call tools
console.log(await bridge.callTool('battery'));
console.log(await bridge.callTool('get_location'));
console.log(await bridge.callTool('toggle_flashlight', { state: 'on' }));

// Vision
console.log(await bridge.callTool('take_photo'));     // Camera2 capture
console.log(await bridge.callTool('analyze_image'));  // Describe last photo

// Device info
console.log(await bridge.getDeviceState());
console.log(bridge.listTools());

bridge.disconnect();
```

## Bidirectional Agent

```javascript
const { LuiBridge } = require('lui-bridge');

const bridge = new LuiBridge('ws://PHONE_IP:8765', 'YOUR_TOKEN', {
  agentName: 'my-bot',
  onInstruction: async (instruction) => {
    console.log(`Got: ${instruction}`);
    return `Executed: ${instruction}`;
  },
  onEvent: (type, data) => {
    if (type === 'notification_2fa') console.log(`2FA: ${data.code}`);
    if (type === 'notification') console.log(`Notif: ${data.title}`);
  }
});

await bridge.connect();
// Phone user says: "patch me to my-bot" → direct chat
// "@my-bot deploy" → one-off instruction
```

## Requirements

- Node.js 16+
- Phone running LUI with bridge enabled
- Same Wi-Fi (or relay for remote)
