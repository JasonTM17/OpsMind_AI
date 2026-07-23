package ai.opsmind.toolgateway.connectors;

import ai.opsmind.toolgateway.application.ToolManifest;
import ai.opsmind.toolgateway.domain.ToolExecutionRequest;

public interface ToolConnector {

    String id();

    default boolean available() {
        return true;
    }

    ConnectorEvidence execute(ToolExecutionRequest request, ToolManifest manifest);
}
