package ai.opsmind.platform.analysis;

public interface AnalysisRuntimeClient {

    AnalysisRuntimeResponse analyze(
        PreparedAnalysisRequest request,
        String capabilityToken,
        String correlationId
    );
}
