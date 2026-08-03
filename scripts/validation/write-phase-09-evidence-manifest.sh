#!/usr/bin/env bash
set -euo pipefail

if [ "$#" -lt 4 ]; then
  echo "Usage: $0 <output> <lane> <result> <command> [command...]" >&2
  exit 2
fi

output=$1
lane=$2
result=$3
shift 3

: "${GITHUB_SHA:?GITHUB_SHA is required}"
: "${PHASE9_EVIDENCE_STARTED_AT_UTC:?PHASE9_EVIDENCE_STARTED_AT_UTC is required}"

mkdir -p "$(dirname "$output")"
{
  echo "Schema=opsmind-phase9-evidence-v1"
  echo "Lane=$lane"
  echo "CommitSha=$GITHUB_SHA"
  echo "StartedAtUtc=$PHASE9_EVIDENCE_STARTED_AT_UTC"
  echo "CompletedAtUtc=$(date -u +%Y-%m-%dT%H:%M:%SZ)"
  echo "Result=$result"
  echo "TemporalImage=temporalio/temporal@sha256:2aeb97183876db2d80abc2e8b30c2157b5b7da00d53576e3eb40b972311db801"
  echo "PrometheusImage=prom/prometheus:v3.12.0-distroless@sha256:f39df5334dee301b885f77e0ff1159f5d8a43bf9db518f885544594799a1e3c2"
  echo "AlertmanagerImage=prom/alertmanager:v0.28.1@sha256:27c475db5fb156cab31d5c18a4251ac7ed567746a2483ff264516437a39b15ba"
  for file in \
    compose.yaml \
    services/platform-api/src/main/resources/application.yaml \
    deploy/prometheus/prometheus.yml \
    deploy/prometheus/opsmind-recording-rules.yml \
    deploy/prometheus/opsmind-reconciliation-alerts.yml \
    deploy/alertmanager/alertmanager.yml; do
    printf 'FileSha256=%s:' "$file"
    sha256sum "$file" | awk '{print $1}'
  done
  for command in "$@"; do
    echo "Command=$command"
  done
} > "$output"
