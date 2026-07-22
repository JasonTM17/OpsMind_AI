package ai.opsmind.toolgateway.application;

import ai.opsmind.toolgateway.domain.DenialCode;
import ai.opsmind.toolgateway.domain.ToolDeniedException;
import ai.opsmind.toolgateway.domain.ToolExecutionRequest;

public final class FailClosedCapabilityVerifier implements DelegatedCapabilityVerifier {

    @Override
    public VerifiedCapability verify(String token, ToolExecutionRequest request) {
        throw new ToolDeniedException(
            DenialCode.CAPABILITY_UNAVAILABLE,
            "Delegated capability verification is unavailable."
        );
    }
}
