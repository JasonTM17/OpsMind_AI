package ai.opsmind.platform.investigation.application;

import ai.opsmind.platform.investigation.projection.InvestigationRunReadModel;

public record InvestigationStartResult(
    InvestigationRunReadModel investigation,
    boolean asynchronous
) {
    public InvestigationStartResult {
        if (investigation == null) {
            throw new IllegalArgumentException("Investigation projection is required.");
        }
    }
}
