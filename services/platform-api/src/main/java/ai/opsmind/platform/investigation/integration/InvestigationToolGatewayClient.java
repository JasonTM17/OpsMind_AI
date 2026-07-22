package ai.opsmind.platform.investigation.integration;

import java.util.UUID;

import ai.opsmind.platform.analysis.AnalysisRuntimeResponse;

/** Port for one-use, read-only tool execution. */
public interface InvestigationToolGatewayClient {

    ToolEvidence execute(AnalysisRuntimeResponse.ToolIntent intent, UUID runId);

    record ToolEvidence(UUID intentId, UUID evidenceId, String digest, String sourceType) { }
}
