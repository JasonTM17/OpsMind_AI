package ai.opsmind.toolgateway.api;

import java.util.List;

import ai.opsmind.toolgateway.application.ExecutionReceiptStore;
import ai.opsmind.toolgateway.application.NonceReplayStore;
import ai.opsmind.toolgateway.application.ToolManifestRegistry;
import ai.opsmind.toolgateway.audit.ToolAuditWriter;
import ai.opsmind.toolgateway.config.GatewaySettings;
import ai.opsmind.toolgateway.connectors.ToolConnector;

public final class GatewayReadiness {

    private final GatewaySettings settings;
    private final NonceReplayStore nonceStore;
    private final ExecutionReceiptStore receiptStore;
    private final ToolAuditWriter auditWriter;
    private final ToolManifestRegistry manifests;
    private final List<ToolConnector> connectors;

    public GatewayReadiness(
        GatewaySettings settings,
        NonceReplayStore nonceStore,
        ExecutionReceiptStore receiptStore,
        ToolAuditWriter auditWriter,
        ToolManifestRegistry manifests,
        List<ToolConnector> connectors
    ) {
        this.settings = settings;
        this.nonceStore = nonceStore;
        this.receiptStore = receiptStore;
        this.auditWriter = auditWriter;
        this.manifests = manifests;
        this.connectors = List.copyOf(connectors);
    }

    public boolean ready() {
        return settings.jwkSetUri() != null
            && settings.workloadJwkSetUri() != null
            && nonceStore.available()
            && receiptStore.available()
            && auditWriter.available()
            && manifests.hasEnabledActions()
            && !connectors.isEmpty();
    }
}
