function requireMarkers(contents, label, markers, errors) {
  for (const marker of markers) {
    if (!contents.includes(marker)) errors.push(`${label} is missing a required audit marker`);
  }
}

export function inspectAuditPersistenceContracts({
  migration,
  portableRunner,
  windowsRunner,
  auditRepository,
  errors,
}) {
  requireMarkers(migration, "V003 migration", [
    "ADD COLUMN schema_version varchar(64)",
    "'tenantSequenceNo', p_tenant_sequence_no",
    "'schemaVersion', p_schema_version",
    "REVOKE INSERT ON audit_events FROM opsmind_app",
    "audit_events_incident_contract",
    "NEW.schema_version IS DISTINCT FROM 'incident-audit-v1'",
    "opsmind_resolve_incident_access",
    "FOR SHARE OF user_row, organization_membership, organization_row",
    "timeline payload does not match the authoritative incident event",
    "NEW.payload IS DISTINCT FROM timeline_row.payload",
  ], errors);
  if (migration.includes("'sequenceNo', p_sequence_no")) {
    errors.push("audit digest binds a database-global sequence");
  }

  const insertGrant = migration.match(/GRANT INSERT \(([\s\S]*?)\) ON audit_events TO opsmind_app;/u)?.[1] ?? "";
  requireMarkers(insertGrant, "audit column insert grant", [
    "event_id", "organization_id", "actor_id", "schema_version", "payload",
  ], errors);
  for (const forbidden of [
    "sequence_no", "tenant_sequence_no", "previous_digest", "event_digest",
  ]) {
    if (insertGrant.includes(forbidden)) {
      errors.push(`runtime audit insert grant exposes ${forbidden}`);
    }
  }

  requireMarkers(portableRunner, "portable Phase 4 runner", [
    "event_row.tenant_sequence_no, event_row.schema_version",
  ], errors);
  if (portableRunner.includes("event_row.sequence_no, event_row.tenant_sequence_no")) {
    errors.push("portable Phase 4 runner uses the retired audit digest signature");
  }
  requireMarkers(windowsRunner, "Windows Phase 4 runner", [
    "event_row.tenant_sequence_no, event_row.schema_version",
    "IncidentHttpPersistenceIntegrationTest",
  ], errors);
  requireMarkers(auditRepository, "audit repository", [
    "schema_version", "event.schemaVersion()",
  ], errors);
}
