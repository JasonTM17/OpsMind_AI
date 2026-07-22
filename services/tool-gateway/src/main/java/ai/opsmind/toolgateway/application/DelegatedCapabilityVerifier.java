package ai.opsmind.toolgateway.application;

import ai.opsmind.toolgateway.domain.ToolExecutionRequest;

@FunctionalInterface
public interface DelegatedCapabilityVerifier {

    VerifiedCapability verify(String token, ToolExecutionRequest request);
}
