package ai.opsmind.platform.investigation.workflow;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.UUID;

import ai.opsmind.platform.common.api.RequestDigest;
import ai.opsmind.platform.incident.AuthorizedIncidentAnalysisEvidence;
import ai.opsmind.platform.incident.IncidentSeverity;
import ai.opsmind.platform.incident.IncidentStatus;
import ai.opsmind.platform.investigation.domain.InvestigationCommand;
import ai.opsmind.platform.messaging.EventEnvelope;

import org.junit.jupiter.api.Test;

import tools.jackson.databind.json.JsonMapper;

class InvestigationWorkflowStartEventCodecTest {

    private final JsonMapper mapper = JsonMapper.builder().findAndAddModules().build();
    private final InvestigationWorkflowStartEnvelopeFactory factory =
        new InvestigationWorkflowStartEnvelopeFactory(mapper);
    private final InvestigationWorkflowStartEventCodec codec =
        new InvestigationWorkflowStartEventCodec(mapper);

    @Test
    void acceptsOnlyPinnedWorkflowStartEnvelope() {
        var prepared = factory.prepare(command(), authorized(), properties());
        var decoded = codec.decode(prepared.event());

        assertThat(decoded.request()).isEqualTo(prepared.request());
        assertThat(decoded.startPayloadDigest()).hasSize(64);
    }

    @Test
    void rejectsUnrelatedEventBeforeTemporal() {
        String payload = "{\"safe\":true}";
        EventEnvelope unrelated = new EventEnvelope(
            UUID.randomUUID(), command().organizationId(), "other", command().runId(), 1,
            "other.event", "1", null, command().runId(), command().startedAt(), payload,
            RequestDigest.sha256(payload.getBytes(StandardCharsets.UTF_8))
        );

        assertThatThrownBy(() -> codec.decode(unrelated))
            .isInstanceOfSatisfying(
                InvestigationWorkflowStartException.class,
                exception -> assertThat(exception.code())
                    .isEqualTo("workflow.event-contract-invalid")
            );
    }

    private InvestigationCommand.Start command() {
        return new InvestigationCommand.Start(
            UUID.fromString("55555555-5555-4555-8555-555555555555"),
            UUID.fromString("11111111-1111-4111-8111-111111111111"),
            UUID.fromString("22222222-2222-4222-8222-222222222222"),
            UUID.fromString("33333333-3333-4333-8333-333333333333"),
            UUID.fromString("44444444-4444-4444-8444-444444444444"),
            new InvestigationCommand.Budget(4, 2, 10, 1_000),
            Instant.parse("2030-01-01T00:00:00Z"),
            Instant.parse("2030-01-01T00:10:00Z")
        );
    }

    private AuthorizedIncidentAnalysisEvidence authorized() {
        var command = command();
        return new AuthorizedIncidentAnalysisEvidence(
            command.organizationId(), command.projectId(), command.incidentId(), command.actorId(),
            "Sensitive", "Sensitive", IncidentSeverity.SEV1,
            IncidentStatus.INVESTIGATING, null, null, 7
        );
    }

    private InvestigationWorkflowProperties properties() {
        return new InvestigationWorkflowProperties(
            "temporal-test", "default", "opsmind-investigation-v1", "investigation-test"
        );
    }
}
