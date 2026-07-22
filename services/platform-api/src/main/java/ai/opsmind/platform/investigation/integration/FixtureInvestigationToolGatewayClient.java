package ai.opsmind.platform.investigation.integration;

import java.time.Instant;
import java.util.UUID;

import ai.opsmind.platform.analysis.AnalysisRuntimeResponse;
import ai.opsmind.platform.evidence.CollectedEvidence;
import ai.opsmind.platform.evidence.EvidenceIdentity;

/** Deterministic read-only fixture adapter; no network or write-capable tool path exists. */
public final class FixtureInvestigationToolGatewayClient implements InvestigationToolGatewayClient {

    @Override
    public ToolEvidence execute(
        AnalysisRuntimeResponse.ToolIntent intent,
        ToolExecutionContext context
    ) {
        if (!"metrics".equals(intent.connector()) || !"query".equals(intent.operation())) {
            throw new IllegalArgumentException("Fixture slice only permits metrics.query.");
        }
        UUID evidenceId = EvidenceIdentity.evidenceId(
            context.organizationId(), context.runId(), intent.intentId()
        );
        UUID executionId = EvidenceIdentity.executionId(
            context.organizationId(), context.runId(), intent.intentId()
        );
        Instant observedAt = Instant.parse("2030-01-01T00:00:00Z");
        CollectedEvidence collected = new CollectedEvidence(
            executionId,
            UUID.nameUUIDFromBytes(("gateway-audit:" + executionId).getBytes(java.nio.charset.StandardCharsets.UTF_8)),
            "0000000000000000000000000000000000000000000000000000000000000000",
            "metric",
            "fixture-prometheus",
            "prometheus:synthetic/opsmind-api",
            observedAt,
            observedAt.minusSeconds(180),
            observedAt,
            "fixture-observability@1",
            "observability.metrics.query@1",
            "policy-fixture-v1",
            "fixture-prometheus/fixture-observability@1",
            "synthetic",
            FixtureInvestigationAiRuntimeClient.evidenceDigest(),
            FixtureInvestigationAiRuntimeClient.evidenceContent(),
            0,
            false,
            null,
            false
        );
        return new ToolEvidence(
            intent.intentId(), evidenceId, FixtureInvestigationAiRuntimeClient.evidenceDigest(),
            "metric", collected
        );
    }
}
