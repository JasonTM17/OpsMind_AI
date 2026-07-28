package ai.opsmind.platform.investigation.application;

import java.sql.Timestamp;
import java.util.List;
import java.util.Optional;

import ai.opsmind.platform.common.api.RequestDigest;
import ai.opsmind.platform.investigation.domain.InvestigationCommand;
import ai.opsmind.platform.investigation.workflow.InvestigationWorkflowStartEnvelopeFactory.PreparedStart;
import ai.opsmind.platform.investigation.workflow.InvestigationWorkflowStartRequest;

import org.springframework.jdbc.core.JdbcTemplate;

final class JdbcInvestigationWorkflowBindingStore {

    private final JdbcTemplate jdbcTemplate;

    JdbcInvestigationWorkflowBindingStore(JdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
    }

    Optional<StoredBinding> find(InvestigationCommand.Start command) {
        List<StoredBinding> bindings = jdbcTemplate.query(
            "SELECT client_request_digest, status "
                + "FROM investigation_workflow_bindings "
                + "WHERE organization_id = ? AND run_id = ?",
            (resultSet, rowNumber) -> new StoredBinding(
                resultSet.getBytes("client_request_digest"),
                resultSet.getString("status")
            ),
            command.organizationId(),
            command.runId()
        );
        return bindings.stream().findFirst();
    }

    boolean hasUnboundNonterminalRun() {
        return Boolean.TRUE.equals(jdbcTemplate.queryForObject(
            "SELECT EXISTS ("
                + "SELECT 1 FROM investigation_runs run "
                + "LEFT JOIN investigation_workflow_bindings binding "
                + "ON binding.organization_id = run.organization_id "
                + "AND binding.run_id = run.run_id "
                + "WHERE run.status IN ('CREATED', 'ANALYZING', 'WAITING_FOR_EVIDENCE') "
                + "AND binding.run_id IS NULL"
                + ")",
            Boolean.class
        ));
    }

    boolean insert(InvestigationCommand.Start command, PreparedStart prepared) {
        InvestigationWorkflowStartRequest request = prepared.request();
        return jdbcTemplate.update(
            "INSERT INTO investigation_workflow_bindings "
                + "(organization_id, run_id, project_id, incident_id, actor_id, "
                + "client_request_digest, start_payload_digest, start_event_id, "
                + "temporal_cluster_id, temporal_namespace, workflow_id, workflow_type, task_queue, "
                + "authorization_revision, status, started_at, deadline_at) "
                + "VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, 'PENDING', ?, ?)",
            request.organizationId(),
            request.runId(),
            request.projectId(),
            request.incidentId(),
            request.actorId(),
            prepared.clientRequestDigest(),
            prepared.payloadDigest(),
            prepared.event().eventId(),
            request.temporalClusterId(),
            request.temporalNamespace(),
            request.workflowId(),
            request.workflowType(),
            request.taskQueue(),
            request.authorizationRevision(),
            Timestamp.from(command.startedAt()),
            Timestamp.from(command.deadlineAt())
        ) == 1;
    }

    record StoredBinding(byte[] clientRequestDigest, String status) {
        StoredBinding {
            clientRequestDigest = RequestDigest.copyAndValidate(clientRequestDigest);
        }

        @Override
        public byte[] clientRequestDigest() {
            return clientRequestDigest.clone();
        }
    }
}
