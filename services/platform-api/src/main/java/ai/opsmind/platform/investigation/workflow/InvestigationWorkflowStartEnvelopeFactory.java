package ai.opsmind.platform.investigation.workflow;

import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.HexFormat;
import java.util.UUID;

import ai.opsmind.platform.common.api.PlatformProblemException;
import ai.opsmind.platform.common.api.RequestDigest;
import ai.opsmind.platform.incident.AuthorizedIncidentAnalysisEvidence;
import ai.opsmind.platform.investigation.domain.InvestigationCommand;
import ai.opsmind.platform.messaging.EventEnvelope;

import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;

import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.annotation.JsonPropertyOrder;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.ObjectMapper;

@Component
public final class InvestigationWorkflowStartEnvelopeFactory {

    public static final String AGGREGATE_TYPE = "investigation-workflow";
    public static final String EVENT_TYPE = "investigation.workflow-start.requested";
    public static final String SCHEMA_VERSION = "1";
    private static final HexFormat HEX_FORMAT = HexFormat.of();

    private final ObjectMapper objectMapper;

    public InvestigationWorkflowStartEnvelopeFactory(ObjectMapper objectMapper) {
        this.objectMapper = objectMapper;
    }

    public PreparedStart prepare(
        InvestigationCommand.Start command,
        AuthorizedIncidentAnalysisEvidence authorized,
        InvestigationWorkflowProperties properties
    ) {
        requireMatchingAuthorization(command, authorized);
        properties.validateStartTarget();

        byte[] clientRequestBytes = serialize(clientRequest(command));
        byte[] clientRequestDigest = RequestDigest.sha256(clientRequestBytes);
        InvestigationWorkflowStartRequest request =
            startRequest(command, authorized, properties, clientRequestDigest);
        byte[] payloadBytes = serialize(request);
        String payloadJson = new String(payloadBytes, StandardCharsets.UTF_8);
        byte[] payloadDigest = RequestDigest.sha256(payloadBytes);
        EventEnvelope event = startEvent(command, payloadJson, payloadDigest);
        return new PreparedStart(request, clientRequestDigest, payloadDigest, event);
    }

    private ClientRequestContract clientRequest(InvestigationCommand.Start command) {
        return new ClientRequestContract(
            command.organizationId(),
            command.projectId(),
            command.incidentId(),
            command.runId(),
            command.actorId(),
            command.budget().maxRounds(),
            command.budget().maxToolCalls(),
            command.budget().maxEvidenceItems(),
            command.budget().maxTokens(),
            command.deadlineAt()
        );
    }

    private InvestigationWorkflowStartRequest startRequest(
        InvestigationCommand.Start command,
        AuthorizedIncidentAnalysisEvidence authorized,
        InvestigationWorkflowProperties properties,
        byte[] clientRequestDigest
    ) {
        return new InvestigationWorkflowStartRequest(
            command.organizationId(),
            command.projectId(),
            command.incidentId(),
            command.runId(),
            command.actorId(),
            command.budget().maxRounds(),
            command.budget().maxToolCalls(),
            command.budget().maxEvidenceItems(),
            command.budget().maxTokens(),
            command.startedAt(),
            command.deadlineAt(),
            properties.clusterId(),
            properties.namespace(),
            InvestigationWorkflowStartRequest.workflowId(command.organizationId(), command.runId()),
            properties.workflowType(),
            properties.taskQueue(),
            authorized.version(),
            HEX_FORMAT.formatHex(clientRequestDigest)
        );
    }

    private EventEnvelope startEvent(
        InvestigationCommand.Start command,
        String payloadJson,
        byte[] payloadDigest
    ) {
        UUID eventId = UUID.nameUUIDFromBytes(
            (EVENT_TYPE + ":" + command.organizationId() + ":" + command.runId())
                .getBytes(StandardCharsets.UTF_8)
        );
        return new EventEnvelope(
            eventId,
            command.organizationId(),
            AGGREGATE_TYPE,
            command.runId(),
            1,
            EVENT_TYPE,
            SCHEMA_VERSION,
            null,
            command.runId(),
            command.startedAt(),
            payloadJson,
            payloadDigest
        );
    }

    private byte[] serialize(Object value) {
        try {
            return objectMapper.writeValueAsBytes(value);
        }
        catch (JacksonException exception) {
            throw new PlatformProblemException(
                HttpStatus.INTERNAL_SERVER_ERROR,
                "investigation.workflow-serialization-failed",
                "Investigation workflow handoff could not be serialized safely.",
                exception
            );
        }
    }

    private void requireMatchingAuthorization(
        InvestigationCommand.Start command,
        AuthorizedIncidentAnalysisEvidence authorized
    ) {
        if (authorized == null
            || !command.organizationId().equals(authorized.organizationId())
            || !command.projectId().equals(authorized.projectId())
            || !command.incidentId().equals(authorized.incidentId())
            || !command.actorId().equals(authorized.actorId())) {
            throw new IllegalArgumentException("Workflow authorization snapshot does not match the run.");
        }
    }

    public record PreparedStart(
        InvestigationWorkflowStartRequest request,
        byte[] clientRequestDigest,
        byte[] payloadDigest,
        EventEnvelope event
    ) {
        public PreparedStart {
            if (request == null || event == null) {
                throw new IllegalArgumentException("Prepared workflow start is incomplete.");
            }
            clientRequestDigest = RequestDigest.copyAndValidate(clientRequestDigest);
            payloadDigest = RequestDigest.copyAndValidate(payloadDigest);
        }

        @Override
        public byte[] clientRequestDigest() {
            return clientRequestDigest.clone();
        }

        @Override
        public byte[] payloadDigest() {
            return payloadDigest.clone();
        }
    }

    @JsonPropertyOrder({
        "organization_id", "project_id", "incident_id", "run_id", "actor_id",
        "max_rounds", "max_tool_calls", "max_evidence_items", "max_tokens", "deadline_at"
    })
    private record ClientRequestContract(
        @JsonProperty("organization_id") UUID organizationId,
        @JsonProperty("project_id") UUID projectId,
        @JsonProperty("incident_id") UUID incidentId,
        @JsonProperty("run_id") UUID runId,
        @JsonProperty("actor_id") UUID actorId,
        @JsonProperty("max_rounds") int maxRounds,
        @JsonProperty("max_tool_calls") int maxToolCalls,
        @JsonProperty("max_evidence_items") int maxEvidenceItems,
        @JsonProperty("max_tokens") int maxTokens,
        @JsonProperty("deadline_at") Instant deadlineAt
    ) { }
}
