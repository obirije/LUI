#!/bin/bash
# Connect Hermes agent to LUI bridge
# Usage: ./connect_hermes.sh 192.168.1.91 YOUR_TOKEN

PHONE_IP="${1:?Usage: $0 PHONE_IP TOKEN}"
TOKEN="${2:?Usage: $0 PHONE_IP TOKEN}"

python3 -u "$(dirname "$0")/lui_connector.py" \
    --url "ws://$PHONE_IP:8765" \
    --token "$TOKEN" \
    --name hermes
