package ai.opsmind.platform.investigation.workflow;

public interface InvestigationWorkflowClient {

    StartResult start(InvestigationWorkflowStartRequest request, String startPayloadDigest);

    record StartResult(String temporalRunId, boolean alreadyStarted) {
        public StartResult {
            if (temporalRunId == null || temporalRunId.isBlank() || temporalRunId.length() > 255) {
                throw new IllegalArgumentException("Temporal run identity is invalid.");
            }
        }
    }
}
