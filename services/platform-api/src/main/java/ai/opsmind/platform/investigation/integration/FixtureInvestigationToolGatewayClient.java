package ai.opsmind.platform.investigation.integration;

import java.util.UUID;

import ai.opsmind.platform.analysis.AnalysisRuntimeResponse;

/** Deterministic read-only fixture adapter; no network or write-capable tool path exists. */
public final class FixtureInvestigationToolGatewayClient implements InvestigationToolGatewayClient {

    @Override
    public ToolEvidence execute(AnalysisRuntimeResponse.ToolIntent intent, UUID runId) {
        if (!"metrics".equals(intent.connector()) || !"query".equals(intent.operation())) {
            throw new IllegalArgumentException("Fixture slice only permits metrics.query.");
        }
        return new ToolEvidence(
            intent.intentId(), FixtureInvestigationAiRuntimeClient.evidenceId(),
            FixtureInvestigationAiRuntimeClient.evidenceDigest(), "metric"
        );
    }
}
