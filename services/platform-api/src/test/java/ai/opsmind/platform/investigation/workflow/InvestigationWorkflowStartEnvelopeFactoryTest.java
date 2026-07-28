package ai.opsmind.platform.investigation.workflow;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Instant;
import java.util.HexFormat;
import java.util.UUID;

import ai.opsmind.platform.incident.AuthorizedIncidentAnalysisEvidence;
import ai.opsmind.platform.incident.IncidentSeverity;
import ai.opsmind.platform.incident.IncidentStatus;
import ai.opsmind.platform.investigation.domain.InvestigationCommand;

import org.junit.jupiter.api.Test;

import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

class InvestigationWorkflowStartEnvelopeFactoryTest {

    private static final UUID ORGANIZATION_ID =
        UUID.fromString("11111111-1111-4111-8111-111111111111");
    private static final UUID PROJECT_ID =
        UUID.fromString("22222222-2222-4222-8222-222222222222");
    private static final UUID INCIDENT_ID =
        UUID.fromString("33333333-3333-4333-8333-333333333333");
    private static final UUID ACTOR_ID =
        UUID.fromString("44444444-4444-4444-8444-444444444444");
    private static final UUID RUN_ID =
        UUID.fromString("55555555-5555-4555-8555-555555555555");
    private static final Instant STARTED_AT = Instant.parse("2030-01-01T00:00:00Z");
    private static final Instant DEADLINE_AT = Instant.parse("2030-01-01T00:10:00Z");

    private final JsonMapper objectMapper = JsonMapper.builder().findAndAddModules().build();
    private final InvestigationWorkflowStartEnvelopeFactory factory =
        new InvestigationWorkflowStartEnvelopeFactory(objectMapper);

    @Test
    void emitsPinnedMinimalPayloadWithDeterministicIdentityAndDigest() throws Exception {
        var first = factory.prepare(command(STARTED_AT), authorized(), properties());
        var second = factory.prepare(command(STARTED_AT), authorized(), properties());

        assertThat(first.event().eventId()).isEqualTo(second.event().eventId());
        assertThat(first.event().payloadJson()).isEqualTo(second.event().payloadJson());
        assertThat(first.payloadDigest()).containsExactly(second.payloadDigest());
        assertThat(first.clientRequestDigest()).containsExactly(second.clientRequestDigest());
        assertThat(first.request().requestDigest())
            .isEqualTo(HexFormat.of().formatHex(first.clientRequestDigest()));
        assertThat(first.request().workflowId())
            .isEqualTo("opsmind-investigation/" + ORGANIZATION_ID + "/" + RUN_ID);

        JsonNode payload = objectMapper.readTree(first.event().payloadJson());
        assertThat(payload.propertyNames()).containsExactly(
            "organization_id", "project_id", "incident_id", "run_id", "actor_id",
            "max_rounds", "max_tool_calls", "max_evidence_items", "max_tokens",
            "started_at", "deadline_at", "temporal_cluster_id", "temporal_namespace",
            "workflow_id", "workflow_type", "task_queue", "authorization_revision",
            "request_digest"
        );
        assertThat(first.event().payloadJson())
            .doesNotContain("title", "summary", "root_cause", "resolution_summary");
    }

    @Test
    void retryDigestIgnoresServerAssignedStartTimeButPayloadRemainsAuditable() {
        var first = factory.prepare(command(STARTED_AT), authorized(), properties());
        var retry = factory.prepare(command(STARTED_AT.plusSeconds(5)), authorized(), properties());

        assertThat(retry.clientRequestDigest()).containsExactly(first.clientRequestDigest());
        assertThat(retry.event().eventId()).isEqualTo(first.event().eventId());
        assertThat(retry.payloadDigest()).isNotEqualTo(first.payloadDigest());
    }

    @Test
    void disabledTemporalTargetFailsClosedBeforePersistence() {
        InvestigationWorkflowProperties disabled =
            new InvestigationWorkflowProperties(null, null, null, null);

        assertThatThrownBy(() -> factory.prepare(command(STARTED_AT), authorized(), disabled))
            .isInstanceOf(IllegalStateException.class)
            .hasMessageContaining("disabled");
    }

    private InvestigationCommand.Start command(Instant startedAt) {
        return new InvestigationCommand.Start(
            RUN_ID, ORGANIZATION_ID, PROJECT_ID, INCIDENT_ID, ACTOR_ID,
            new InvestigationCommand.Budget(4, 2, 10, 1_000),
            startedAt, DEADLINE_AT
        );
    }

    private AuthorizedIncidentAnalysisEvidence authorized() {
        return new AuthorizedIncidentAnalysisEvidence(
            ORGANIZATION_ID, PROJECT_ID, INCIDENT_ID, ACTOR_ID,
            "Sensitive title", "Sensitive summary", IncidentSeverity.SEV1,
            IncidentStatus.INVESTIGATING, "Sensitive root cause", null, 7
        );
    }

    private InvestigationWorkflowProperties properties() {
        return new InvestigationWorkflowProperties(
            "temporal-primary", "opsmind-prod", "opsmind-investigation-v1",
            "opsmind-investigation-prod"
        );
    }
}
