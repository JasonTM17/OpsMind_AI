package ai.opsmind.platform.investigation.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

import ai.opsmind.platform.analysis.AnalysisRuntimeResponse;
import ai.opsmind.platform.investigation.domain.InvestigationEvent;

import org.junit.jupiter.api.Test;

import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

class InvestigationAcceptedAnalysisEventSerializationTest {

    private static final UUID RUN_ID = UUID.randomUUID();
    private static final Instant OCCURRED_AT = Instant.parse("2030-01-01T00:00:01Z");

    private final JsonMapper objectMapper = JsonMapper.builder().findAndAddModules().build();
    private final InvestigationPersistenceJsonCodec codec =
        new InvestigationPersistenceJsonCodec(objectMapper);

    @Test
    void acceptedEventCarriesExactCompleteNeedMoreAndAbstainResponses() throws Exception {
        for (AnalysisRuntimeResponse response : List.of(
            completeResponse(), needMoreEvidenceResponse(), abstainResponse()
        )) {
            InvestigationEvent.AnalysisAccepted event = new InvestigationEvent.AnalysisAccepted(
                RUN_ID, response.status(), 2, 19, response, OCCURRED_AT
            );

            String payload = codec.eventPayload(
                UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID(),
                RUN_ID, 4, UUID.randomUUID(), event
            );
            JsonNode details = objectMapper.readTree(payload).get("details");

            assertThat(details.propertyNames()).containsExactlyInAnyOrder(
                "runId", "status", "round", "totalTokens", "response", "occurredAt"
            );
            assertThat(details.get("status").asText()).isEqualTo(response.status());
            assertThat(codec.readFinalResponse(details.get("response").toString()))
                .isEqualTo(response);
            assertThat(event.response()).isSameAs(response);
        }
    }

    @Test
    void acceptedEventRejectsStatusAndRunIdentityDrift() {
        AnalysisRuntimeResponse response = abstainResponse();

        assertThatThrownBy(() -> new InvestigationEvent.AnalysisAccepted(
            RUN_ID, "complete", 1, 1, response, OCCURRED_AT
        )).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new InvestigationEvent.AnalysisAccepted(
            UUID.randomUUID(), response.status(), 1, 1, response, OCCURRED_AT
        )).isInstanceOf(IllegalArgumentException.class);
    }

    private AnalysisRuntimeResponse completeResponse() {
        AnalysisRuntimeResponse.Citation citation = new AnalysisRuntimeResponse.Citation(
            UUID.randomUUID(), digest('1'), "Latency rose after the deployment."
        );
        return response(
            "complete",
            List.of(new AnalysisRuntimeResponse.Hypothesis(
                "Deployment regression", "The deployment correlates with the latency increase.",
                0.8, List.of(citation)
            )),
            List.of(),
            List.of(),
            List.of(citation),
            0.8,
            List.of()
        );
    }

    private AnalysisRuntimeResponse needMoreEvidenceResponse() {
        return response(
            "need_more_evidence", List.of(), List.of(), List.of("Service latency metric"),
            List.of(), 0.2, List.of(new AnalysisRuntimeResponse.ToolIntent(
                UUID.randomUUID(), "metrics", "query", digest('2'),
                "Inspect the service latency metric."
            ))
        );
    }

    private AnalysisRuntimeResponse abstainResponse() {
        return response(
            "abstain", List.of(), List.of(), List.of("Deployment change record"),
            List.of(), 0.0, List.of()
        );
    }

    private AnalysisRuntimeResponse response(
        String status,
        List<AnalysisRuntimeResponse.Hypothesis> hypotheses,
        List<String> counterEvidence,
        List<String> missingEvidence,
        List<AnalysisRuntimeResponse.Citation> citations,
        double confidence,
        List<AnalysisRuntimeResponse.ToolIntent> toolIntents
    ) {
        return new AnalysisRuntimeResponse(
            status, RUN_ID, "deepseek-v4-flash", "prompt-incident-investigation-v1",
            "analysis-v1", hypotheses, counterEvidence, missingEvidence, citations, confidence,
            new AnalysisRuntimeResponse.Usage(10, 5, 15),
            new AnalysisRuntimeResponse.CostEstimate("USD", BigDecimal.ZERO), toolIntents
        );
    }

    private String digest(char value) {
        return "sha256:" + String.valueOf(value).repeat(64);
    }
}
