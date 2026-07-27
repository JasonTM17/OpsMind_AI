-- Online ordering indexes for the metadata-only incident activity projection.
-- The migration is intentionally non-transactional; rollout must apply it
-- before mixed old/new application versions.
CREATE INDEX CONCURRENTLY incident_timeline_activity_order_idx
    ON incident_timeline_events (organization_id, project_id, incident_id, occurred_at, event_id);

CREATE INDEX CONCURRENTLY investigation_run_events_activity_order_idx
    ON investigation_run_events (organization_id, project_id, incident_id, occurred_at, event_id);
