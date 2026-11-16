#!/usr/bin/env bash
# Print the Slack app manifest with REQUEST_URL filled in from the deployed app stack.
# Usage: scripts/slack-manifest.sh | xclip -selection clipboard     (or pbcopy on macOS)
set -euo pipefail
cd "$(dirname "$0")/.."
REGION="${REGION:-eu-west-1}"; PREFIX="${PREFIX:-slack-agentcore}"
REQUEST_URL=$(aws cloudformation describe-stacks --region "$REGION" --stack-name "$PREFIX-app" \
  --query "Stacks[0].Outputs[?OutputKey=='FunctionUrl'].OutputValue" --output text)
grep -v '^#' slack/manifest.yaml | sed "s|\${REQUEST_URL}|$REQUEST_URL|"
