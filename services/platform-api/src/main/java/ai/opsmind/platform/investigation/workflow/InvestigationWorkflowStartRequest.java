package ai.opsmind.platform.investigation.workflow;

import java.time.Instant;
import java.util.HexFormat;
import java.util.UUID;

import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.annotation.JsonPropertyOrder;

@JsonPropertyOrder({
    "organization_id", "project_id", "incident_id", "run_id", "actor_id",
    "max_rounds", "max_tool_calls", "max_evidence_items", "max_tokens",
    "started_at", "deadline_at", "temporal_cluster_id", "temporal_namespace",
    "workflow_id", "workflow_type", "task_queue", "authorization_revision",
    "request_digest"
})
public record InvestigationWorkflowStartRequest(
    @JsonProperty("organization_id") UUID organizationId,
    @JsonProperty("project_id") UUID projectId,
    @JsonProperty("incident_id") UUID incidentId,
    @JsonProperty("run_id") UUID runId,
    @JsonProperty("actor_id") UUID actorId,
    @JsonProperty("max_rounds") int maxRounds,
    @JsonProperty("max_tool_calls") int maxToolCalls,
    @JsonProperty("max_evidence_items") int maxEvidenceItems,
    @JsonProperty("max_tokens") int maxTokens,
    @JsonProperty("started_at") Instant startedAt,
    @JsonProperty("deadline_at") Instant deadlineAt,
    @JsonProperty("temporal_cluster_id") String temporalClusterId,
    @JsonProperty("temporal_namespace") String temporalNamespace,
    @JsonProperty("workflow_id") String workflowId,
    @JsonProperty("workflow_type") String workflowType,
    @JsonProperty("task_queue") String taskQueue,
    @JsonProperty("authorization_revision") long authorizationRevision,
    @JsonProperty("request_digest") String requestDigest
) {
    private static final HexFormat HEX_FORMAT = HexFormat.of();
    private static final String SHA_256_HEX = "[0-9a-f]{64}";
    private static final String TARGET_NAME = "[A-Za-z0-9][A-Za-z0-9._-]*";

    public InvestigationWorkflowStartRequest {
        if (hasMissingIdentity(
            organizationId, projectId, incidentId, runId, actorId, startedAt, deadlineAt
        ) || !deadlineAt.isAfter(startedAt)
            || maxRounds < 1 || maxRounds > 20
            || maxToolCalls < 0 || maxToolCalls > 20
            || maxEvidenceItems < 1 || maxEvidenceItems > 200
            || maxTokens < 1 || maxTokens > 100_000
            || invalidTarget(temporalClusterId, 128)
            || invalidTarget(temporalNamespace, 255)
            || !workflowId(organizationId, runId).equals(workflowId)
            || invalidTarget(workflowType, 128)
            || invalidTarget(taskQueue, 255)
            || authorizationRevision < 0
            || !isSha256Hex(requestDigest)) {
            throw new IllegalArgumentException("Workflow start request is invalid.");
        }
    }

    public static String workflowId(UUID organizationId, UUID runId) {
        if (organizationId == null || runId == null) {
            throw new IllegalArgumentException("Workflow identity is required.");
        }
        return "opsmind-investigation/" + organizationId + "/" + runId;
    }

    public byte[] requestDigestBytes() {
        return HEX_FORMAT.parseHex(requestDigest);
    }

    private static boolean hasMissingIdentity(
        UUID organizationId,
        UUID projectId,
        UUID incidentId,
        UUID runId,
        UUID actorId,
        Instant startedAt,
        Instant deadlineAt
    ) {
        return organizationId == null
            || projectId == null
            || incidentId == null
            || runId == null
            || actorId == null
            || startedAt == null
            || deadlineAt == null;
    }

    private static boolean invalidTarget(String value, int maximumLength) {
        return value == null || value.length() > maximumLength || !value.matches(TARGET_NAME);
    }

    private static boolean isSha256Hex(String value) {
        return value != null && value.matches(SHA_256_HEX);
    }

}
