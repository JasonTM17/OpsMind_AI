package ai.opsmind.platform.investigation.integration;

import ai.opsmind.platform.analysis.AnalysisRuntimeResponse;

/** Port used by the bounded runner; network/authentication stays outside the reducer. */
public interface InvestigationAiRuntimeClient {

    AnalysisRuntimeResponse analyze(InvestigationAnalysisRequest request);
}
