#!/usr/bin/env bash
# Build + deploy everything: base stack -> agent image (Jib, arm64) -> gateway jar -> app stack.
# Usage: scripts/deploy.sh            (env overrides: REGION, LLM_PROVIDER, BEDROCK_MODEL_ID, ANTHROPIC_MODEL_ID)
set -euo pipefail
cd "$(dirname "$0")/.."

REGION="${REGION:-eu-west-1}"
PREFIX="${PREFIX:-slack-agentcore}"
LLM_PROVIDER="${LLM_PROVIDER:-bedrock-converse}"
BEDROCK_MODEL_ID="${BEDROCK_MODEL_ID:-eu.anthropic.claude-haiku-4-5-20251001-v1:0}"
ANTHROPIC_MODEL_ID="${ANTHROPIC_MODEL_ID:-claude-opus-5-5}"
export AWS_REGION="$REGION"

out() { aws cloudformation describe-stacks --stack-name "$1" --query "Stacks[0].Outputs[?OutputKey=='$2'].OutputValue" --output text; }

echo "==> base stack"
aws cloudformation deploy --stack-name "$PREFIX-base" --template-file infra/base.yaml \
  --parameter-overrides Prefix="$PREFIX" --no-fail-on-empty-changeset
REPO_URI=$(out "$PREFIX-base" RepositoryUri)
REPO_ARN=$(out "$PREFIX-base" RepositoryArn)
BUCKET=$(out "$PREFIX-base" ArtifactsBucket)
SECRET_ARN=$(out "$PREFIX-base" SecretArn)

echo "==> build"
mvn -q -DskipTests=false package

echo "==> push agent image (linux/arm64)"
TAG="$(date +%Y%m%d%H%M%S)-$(sha256sum agent/target/agent-*.jar | cut -c1-8)"
IMAGE="$REPO_URI:$TAG"
mvn -q -pl agent jib:build -Dimage="$IMAGE" \
  -Djib.to.auth.username=AWS -Djib.to.auth.password="$(aws ecr get-login-password)"

echo "==> upload gateway jar"
JAR=slack-gateway/target/slack-gateway.jar
KEY="gateway/$(sha256sum "$JAR" | cut -c1-16).jar"
aws s3 cp --quiet "$JAR" "s3://$BUCKET/$KEY"

echo "==> app stack"
aws cloudformation deploy --stack-name "$PREFIX-app" --template-file infra/app.yaml \
  --capabilities CAPABILITY_IAM --no-fail-on-empty-changeset \
  --parameter-overrides Prefix="$PREFIX" ImageUri="$IMAGE" RepositoryArn="$REPO_ARN" SecretArn="$SECRET_ARN" \
    ArtifactsBucket="$BUCKET" LambdaCodeKey="$KEY" LlmProvider="$LLM_PROVIDER" \
    BedrockModelId="$BEDROCK_MODEL_ID" AnthropicModelId="$ANTHROPIC_MODEL_ID"

# Runtime log groups are created by AgentCore itself; cap their retention (cost).
for lg in $(aws logs describe-log-groups --log-group-name-prefix /aws/bedrock-agentcore/runtimes/ \
    --query 'logGroups[?retentionInDays==`null`].logGroupName' --output text); do
  aws logs put-retention-policy --log-group-name "$lg" --retention-in-days 3
done

echo
echo "Function URL (Slack Events request URL): $(out "$PREFIX-app" FunctionUrl)"
echo "Agent runtime ARN:                       $(out "$PREFIX-app" AgentRuntimeArn)"
echo "Memory id:                               $(out "$PREFIX-app" MemoryId)"
