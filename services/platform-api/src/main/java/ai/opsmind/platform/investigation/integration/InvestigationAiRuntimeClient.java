package ai.opsmind.platform.investigation.integration;

import java.util.Set;
import java.util.UUID;

import ai.opsmind.platform.analysis.AnalysisRuntimeResponse;

/** Port used by the bounded runner; network/authentication stays outside the reducer. */
public interface InvestigationAiRuntimeClient {

    AnalysisRuntimeResponse analyze(UUID runId, Set<UUID> evidenceIds, int round);
}
