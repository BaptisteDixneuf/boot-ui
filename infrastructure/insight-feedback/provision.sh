#!/usr/bin/env bash
# Opt-in deployment. Never run from a CI build: this creates billable Azure resources.
set -euo pipefail

GROUP="${1:?Usage: provision.sh <new-resource-group> <azure-region>}"
LOCATION="${2:?Usage: provision.sh <new-resource-group> <azure-region>}"
[[ "$GROUP" =~ ^[a-zA-Z0-9_-]{3,50}$ ]] || { echo 'Invalid resource group name' >&2; exit 2; }
[[ "$LOCATION" =~ ^[a-z0-9-]{3,40}$ ]] || { echo 'Invalid region' >&2; exit 2; }
command -v az >/dev/null
command -v npm >/dev/null
command -v zip >/dev/null
command -v openssl >/dev/null
# Fail before provisioning anything if not signed in. No keys are written into this repository.
az account show --only-show-errors --output none

SUFFIX="$(openssl rand -hex 4)"
ACCOUNT="buifb${SUFFIX}"
STORAGE="buifbst${SUFFIX}"
FUNCTION="buifbfn${SUFFIX}"
HERE="$(cd "$(dirname "$0")" && pwd)"
TMP="$(mktemp -d)"
trap 'rm -rf "$TMP"' EXIT

az group create --name "$GROUP" --location "$LOCATION" --only-show-errors --output none
az storage account create --name "$STORAGE" --resource-group "$GROUP" --location "$LOCATION" \
  --sku Standard_LRS --kind StorageV2 --only-show-errors --output none
az cosmosdb create --name "$ACCOUNT" --resource-group "$GROUP" --locations "regionName=$LOCATION" \
  --capabilities EnableServerless --only-show-errors --output none
az cosmosdb sql database create --account-name "$ACCOUNT" --resource-group "$GROUP" \
  --name feedback --only-show-errors --output none
az cosmosdb sql container create --account-name "$ACCOUNT" --resource-group "$GROUP" \
  --database-name feedback --name votes --partition-key-path /kind --ttl -1 \
  --only-show-errors --output none
az functionapp create --name "$FUNCTION" --resource-group "$GROUP" --storage-account "$STORAGE" \
  --consumption-plan-location "$LOCATION" --functions-version 4 --runtime node --runtime-version 22 \
  --os-type Linux --only-show-errors --output none
PRINCIPAL="$(az functionapp identity assign --name "$FUNCTION" --resource-group "$GROUP" \
  --query principalId -o tsv --only-show-errors)"
az cosmosdb sql role assignment create --account-name "$ACCOUNT" --resource-group "$GROUP" \
  --role-definition-id 00000000-0000-0000-0000-000000000002 --principal-id "$PRINCIPAL" \
  --scope / --only-show-errors --output none
# Azure CLI passes app settings in process arguments. Do not enable shell tracing or run on a shared host.
KEY="$(openssl rand -base64 32)"
az functionapp config appsettings set --name "$FUNCTION" --resource-group "$GROUP" \
  --settings "COSMOS_ENDPOINT=https://${ACCOUNT}.documents.azure.com" "FEEDBACK_HMAC_KEY=$KEY" \
  "FUNCTIONS_NODE_BLOCK_ON_ENTRY_POINT_ERROR=true" --only-show-errors --output none
unset KEY

cd "$HERE/function"
npm ci --ignore-scripts
npm run build
cp package.json package-lock.json host.json "$TMP/"
cp -R dist "$TMP/"
cd "$TMP"
npm ci --omit=dev --ignore-scripts
zip -q -r deploy.zip host.json package.json package-lock.json dist node_modules
az functionapp deployment source config-zip --name "$FUNCTION" --resource-group "$GROUP" \
  --src deploy.zip --only-show-errors --output none
printf 'Configured Azure Functions origin: https://%s.azurewebsites.net\n' "$FUNCTION"
printf 'Enable locally with bootui.runtime-insights.feedback-endpoint=https://%s.azurewebsites.net\n' "$FUNCTION"
