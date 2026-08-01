export const workerEnvironmentAllowlist = new Set([
  "OPSMIND_SECURITY_MODE",
  "OPSMIND_INVESTIGATION_TEMPORAL_WORKER_ENABLED",
  "OPSMIND_INVESTIGATION_TEMPORAL_CLUSTER_ID",
  "OPSMIND_INVESTIGATION_TEMPORAL_NAMESPACE",
  "OPSMIND_INVESTIGATION_TEMPORAL_WORKFLOW_TYPE",
  "OPSMIND_INVESTIGATION_TEMPORAL_TASK_QUEUE",
  "OPSMIND_INVESTIGATION_TEMPORAL_TARGET",
  "OPSMIND_INVESTIGATION_TEMPORAL_TLS_ENABLED",
  "OPSMIND_INVESTIGATION_TEMPORAL_ALLOW_LOCAL_CLEARTEXT",
  "OPSMIND_INVESTIGATION_TEMPORAL_RPC_TIMEOUT",
  "OPSMIND_INVESTIGATION_TEMPORAL_WORKER_IDENTITY",
  "OPSMIND_INVESTIGATION_TEMPORAL_WORKER_BUILD_ID",
  "OPSMIND_INVESTIGATION_TEMPORAL_WORKER_MAX_CONCURRENT_WORKFLOW_TASK_EXECUTORS",
  "OPSMIND_INVESTIGATION_TEMPORAL_WORKER_MAX_CONCURRENT_WORKFLOW_TASK_POLLERS",
  "OPSMIND_INVESTIGATION_TEMPORAL_WORKER_SHUTDOWN_TIMEOUT",
]);

export function validateWorkerEnvironmentNames(names) {
  const actual = new Set(names);
  const failures = [];
  for (const name of actual) {
    if (!workerEnvironmentAllowlist.has(name)) {
      failures.push(`worker environment contains non-Temporal variable: ${name}`);
    }
  }
  for (const name of workerEnvironmentAllowlist) {
    if (!actual.has(name)) {
      failures.push(`worker environment is missing required variable: ${name}`);
    }
  }
  return failures;
}

export function sensitiveWorkerEnvironmentNames(names) {
  const sensitive = /(?:^|_)(?:PASSWORD|SECRET|TOKEN|KEY)(?:_|$)|^(?:POSTGRES|SPRING|AI|DEEPSEEK|OIDC|TOOL|DATABASE|PG|REDIS|KAFKA|MINIO)_/;
  return names.filter((name) => sensitive.test(name));
}
