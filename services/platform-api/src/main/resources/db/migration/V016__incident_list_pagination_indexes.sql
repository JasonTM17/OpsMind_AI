-- Online indexes for tenant-scoped incident live-view pagination.
-- Apply successfully before enabling the collection read runtime.
CREATE INDEX CONCURRENTLY incident_list_order_idx
    ON incidents (organization_id, project_id, updated_at DESC, id DESC);

CREATE INDEX CONCURRENTLY incident_list_status_order_idx
    ON incidents (organization_id, project_id, status, updated_at DESC, id DESC);
