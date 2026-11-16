#!/usr/bin/env bash
# Store Slack credentials (and optionally the Anthropic key) in the single app secret, prompting without echo.
# Usage: scripts/set-slack-secrets.sh
set -euo pipefail
REGION="${REGION:-eu-west-1}"; PREFIX="${PREFIX:-slack-agentcore}"; export AWS_REGION="$REGION"
current=$(aws secretsmanager get-secret-value --secret-id "$PREFIX" --query SecretString --output text)
read -rsp "Slack signing secret (enter to keep): " SIGNING; echo
read -rsp "Slack bot token xoxb-... (enter to keep): " BOT; echo
read -rsp "Anthropic API key (enter to keep): " ANTH; echo
new=$(SIGNING="$SIGNING" BOT="$BOT" ANTH="$ANTH" python3 -c '
import json,os,sys
d=json.loads(sys.argv[1])
for k,e in [("signingSecret","SIGNING"),("botToken","BOT"),("anthropicApiKey","ANTH")]:
    if os.environ[e]: d[k]=os.environ[e]
print(json.dumps(d))' "$current")
aws secretsmanager put-secret-value --secret-id "$PREFIX" --secret-string "$new" --query VersionId --output text >/dev/null
echo "Secret $PREFIX updated."
