#!/usr/bin/env bash
# Sourced by the disposable V006-to-V009 upgrade harness. PASS requires valid
# fixture writes, persistent app-role JDBC benchmarks, and production-SQL plans.

readonly V009_ORGANIZATION_ID="70000000-0000-4000-8000-000000000001"
readonly V009_PROJECT_ID="70000000-0000-4000-8000-000000000003"
readonly V009_INCIDENT_ID="70000000-0000-4000-8000-000000000014"

run_incident_timeline_v009_evidence() {
  local upgrade_database="$1"
  local database_url="$2"
  local repository_root
  repository_root="$(cd "$(dirname "${BASH_SOURCE[0]}")/../../.." && pwd)"

  require_v009_environment
  seed_v009_ledgers "$repository_root"
  run_append_benchmark "$repository_root" "$database_url" pre_index
  run_v009_recovery "$repository_root" "$database_url"
  assert_v009_catalog
  run_append_benchmark "$repository_root" "$database_url" post_index
  run_plan_and_read_benchmark "$repository_root" "$database_url"
  assert_v009_sample_counts
  emit_v009_metrics "$upgrade_database"
}

require_v009_environment() {
  local required_name
  for required_name in POSTGRES_APP_USER POSTGRES_APP_PASSWORD OPS_CACHE_ROOT; do
    if [[ -z "${!required_name:-}" ]]; then
      echo "${required_name} is required for V009 database evidence." >&2
      return 2
    fi
  done
}

run_upgrade_database_file() {
  local sql_file="$1"
  PGPASSWORD="$POSTGRES_PASSWORD" psql --no-password --no-psqlrc --quiet \
    --host "$PGHOST" --port "$PGPORT" --username "$POSTGRES_USER" \
    --dbname "$upgrade_database" --set ON_ERROR_STOP=1 \
    --set AUTOCOMMIT=on \
    --file "$sql_file"
}

seed_v009_ledgers() {
  local repository_root="$1"
  run_upgrade_database_file \
    "$repository_root/scripts/validation/phase-04b-evidence-records/incident-timeline-v009-seed.sql" \
    >/dev/null

  local incident_rows investigation_rows
  local incident_distractor_rows investigation_distractor_rows
  local max_locks_per_transaction
  incident_rows="$(query_upgrade_database "
SELECT count(*) FROM incident_timeline_events
 WHERE organization_id = '$V009_ORGANIZATION_ID'
   AND project_id = '$V009_PROJECT_ID'
   AND incident_id = '$V009_INCIDENT_ID';")"
  investigation_rows="$(query_upgrade_database "
SELECT count(*) FROM investigation_run_events
 WHERE organization_id = '$V009_ORGANIZATION_ID'
   AND project_id = '$V009_PROJECT_ID'
   AND incident_id = '$V009_INCIDENT_ID';")"
  incident_distractor_rows="$(query_upgrade_database "
SELECT count(*) FROM incident_timeline_events
 WHERE organization_id = '$V009_ORGANIZATION_ID'
   AND project_id = '$V009_PROJECT_ID'
   AND incident_id <> '$V009_INCIDENT_ID'
   AND occurred_at >= '2032-01-01T00:00:00Z'
   AND occurred_at < '2033-01-01T00:00:00Z';")"
  investigation_distractor_rows="$(query_upgrade_database "
SELECT count(*) FROM investigation_run_events
 WHERE organization_id = '$V009_ORGANIZATION_ID'
   AND project_id = '$V009_PROJECT_ID'
   AND incident_id <> '$V009_INCIDENT_ID'
   AND occurred_at >= '2032-01-01T00:00:00Z'
   AND occurred_at < '2033-01-01T00:00:00Z';")"
  max_locks_per_transaction="$(query_upgrade_database "
SELECT current_setting('max_locks_per_transaction')::integer;")"
  [[ "$incident_rows" == "50000" ]]
  [[ "$investigation_rows" == "50000" ]]
  [[ "$incident_distractor_rows" == "10000" ]]
  [[ "$investigation_distractor_rows" == "10000" ]]
  [[ "$max_locks_per_transaction" -ge 50 ]]
  printf '%s\n' \
    "V009SeedIncidentRows=$incident_rows" \
    "V009SeedInvestigationRows=$investigation_rows" \
    "V009SeedIncidentDistractorRows=$incident_distractor_rows" \
    "V009SeedInvestigationDistractorRows=$investigation_distractor_rows" \
    "V009SeedBatchSize=50" \
    "V009SeedMaxLocksPerTransaction=$max_locks_per_transaction"
}

run_append_benchmark() {
  local repository_root="$1"
  local database_url="$2"
  local phase="$3"
  OPSMIND_PHASE4B_APPEND_PHASE="$phase" \
  SPRING_DATASOURCE_URL="$database_url" \
    mvn --batch-mode --no-transfer-progress \
      -Dmaven.repo.local="$OPS_CACHE_ROOT/maven" \
      -f "$repository_root/services/platform-api/pom.xml" \
      -Dsurefire.useFile=false \
      -Dtest=IncidentActivityTimelineAppendBenchmarkHarnessTest test
}

run_v009_recovery() {
  local repository_root="$1"
  local database_url="$2"
  OPSMIND_PHASE4B_RECOVERY_ENABLED=true \
  SPRING_DATASOURCE_URL="$database_url" \
  SPRING_DATASOURCE_USERNAME="$POSTGRES_USER" \
  SPRING_DATASOURCE_PASSWORD="$POSTGRES_PASSWORD" \
    mvn --batch-mode --no-transfer-progress \
      -Dmaven.repo.local="$OPS_CACHE_ROOT/maven" \
      -f "$repository_root/services/platform-api/pom.xml" \
      -Dsurefire.useFile=false \
      -Dtest=FlywayRecoveryHarnessTest test
}

run_plan_and_read_benchmark() {
  local repository_root="$1"
  local database_url="$2"
  OPSMIND_PHASE4B_PLAN_ENABLED=true \
  SPRING_DATASOURCE_URL="$database_url" \
    mvn --batch-mode --no-transfer-progress \
      -Dmaven.repo.local="$OPS_CACHE_ROOT/maven" \
      -f "$repository_root/services/platform-api/pom.xml" \
      -Dsurefire.useFile=false \
      -Dtest=IncidentActivityTimelinePlanHarnessTest test
}

assert_v009_catalog() {
  local catalog_rows
  catalog_rows="$(query_upgrade_database "
SELECT class.relname || ':' || index.indisvalid
  FROM pg_class class
  JOIN pg_index index ON index.indexrelid = class.oid
 WHERE class.relnamespace = 'public'::regnamespace
   AND class.relname IN (
     'incident_timeline_activity_order_idx',
     'investigation_run_events_activity_order_idx'
   )
 ORDER BY class.relname;")"
  printf 'V009PostRecoveryIndexCatalog=%s\n' "${catalog_rows//$'\n'/,}"
  [[ "$catalog_rows" == \
    $'incident_timeline_activity_order_idx:true\ninvestigation_run_events_activity_order_idx:true' ]]
}

assert_sample_count() {
  local sample_kind="$1"
  local sample_phase="$2"
  local expected_count="$3"
  local actual_count
  actual_count="$(query_upgrade_database "
SELECT count(*) FROM phase_v009_samples
 WHERE sample_kind = '$sample_kind'
   AND sample_phase = '$sample_phase';")"
  [[ "$actual_count" == "$expected_count" ]]
}

assert_v009_sample_counts() {
  local kind phase
  for kind in incident_append investigation_append; do
    for phase in pre_index post_index; do
      assert_sample_count "$kind" "${phase}_warmup" 50
      assert_sample_count "$kind" "$phase" 250
    done
  done
  for kind in vendor_read_initial vendor_read_cursor_rank_0 vendor_read_cursor_rank_1; do
    assert_sample_count "$kind" post_index_warmup 50
    assert_sample_count "$kind" post_index 50
  done
}

metric_p95() {
  local sample_kind="$1"
  local sample_phase="$2"
  query_upgrade_database "
SELECT round(
  percentile_cont(0.95) WITHIN GROUP (ORDER BY duration_ms)::numeric,
  3
) FROM phase_v009_samples
 WHERE sample_kind = '$sample_kind'
   AND sample_phase = '$sample_phase';"
}

emit_v009_metrics() {
  local upgrade_database_name="$1"
  local incident_pre investigation_pre incident_post investigation_post
  local initial_read_p95 cursor_rank_0_p95 cursor_rank_1_p95
  local index_bytes source_rows source_bytes
  incident_pre="$(metric_p95 incident_append pre_index)"
  investigation_pre="$(metric_p95 investigation_append pre_index)"
  incident_post="$(metric_p95 incident_append post_index)"
  investigation_post="$(metric_p95 investigation_append post_index)"
  initial_read_p95="$(metric_p95 vendor_read_initial post_index)"
  cursor_rank_0_p95="$(metric_p95 vendor_read_cursor_rank_0 post_index)"
  cursor_rank_1_p95="$(metric_p95 vendor_read_cursor_rank_1 post_index)"
  index_bytes="$(query_upgrade_database "
SELECT sum(pg_relation_size(indexrelid)) FROM pg_index
 WHERE indexrelid IN (
   'public.incident_timeline_activity_order_idx'::regclass,
   'public.investigation_run_events_activity_order_idx'::regclass
 );")"
  source_rows="$(query_upgrade_database "
SELECT (SELECT count(*) FROM incident_timeline_events)
     + (SELECT count(*) FROM investigation_run_events);")"
  source_bytes="$(query_upgrade_database "
SELECT pg_table_size('public.incident_timeline_events')
     + pg_table_size('public.investigation_run_events');")"

  [[ -n "$incident_pre" && -n "$investigation_pre" ]]
  [[ -n "$incident_post" && -n "$investigation_post" ]]
  [[ -n "$initial_read_p95" && -n "$cursor_rank_0_p95" && -n "$cursor_rank_1_p95" ]]
  awk -v pre="$incident_pre" -v post="$incident_post" -v limit=500 \
    'BEGIN { exit !(post <= limit && post <= pre * 1.20) }'
  awk -v pre="$investigation_pre" -v post="$investigation_post" -v limit=500 \
    'BEGIN { exit !(post <= limit && post <= pre * 1.20) }'
  for value in "$initial_read_p95" "$cursor_rank_0_p95" "$cursor_rank_1_p95"; do
    awk -v measured="$value" -v limit=500 \
      'BEGIN { exit !(measured <= limit) }'
  done
  [[ "$index_bytes" -le $((256 * source_rows)) ]]
  [[ "$index_bytes" -le "$source_bytes" ]]

  printf '%s\n' \
    "V009Database=$upgrade_database_name" \
    "IncidentLedgerRows=$(query_upgrade_database "SELECT count(*) FROM incident_timeline_events;")" \
    "InvestigationLedgerRows=$(query_upgrade_database "SELECT count(*) FROM investigation_run_events;")" \
    "IncidentAppendWarmupSamples=50" \
    "IncidentAppendMeasuredSamples=250" \
    "InvestigationAppendWarmupSamples=50" \
    "InvestigationAppendMeasuredSamples=250" \
    "IncidentAppendP95PreMs=$incident_pre" \
    "IncidentAppendP95PostMs=$incident_post" \
    "InvestigationAppendP95PreMs=$investigation_pre" \
    "InvestigationAppendP95PostMs=$investigation_post" \
    "VendorReadPerModeWarmupSamples=50" \
    "VendorReadPerModeMeasuredSamples=50" \
    "VendorReadInitialP95Ms=$initial_read_p95" \
    "VendorReadCursorRank0P95Ms=$cursor_rank_0_p95" \
    "VendorReadCursorRank1P95Ms=$cursor_rank_1_p95" \
    "V009IndexBytes=$index_bytes" \
    "V009SourceRows=$source_rows" \
    "V009SourceTableBytes=$source_bytes" \
    "V009EvidenceResult=PASS"
}
