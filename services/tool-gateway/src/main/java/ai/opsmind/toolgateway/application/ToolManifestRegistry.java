package ai.opsmind.toolgateway.application;

import java.util.Collection;
import java.util.HashMap;
import java.util.Map;

import ai.opsmind.toolgateway.domain.DenialCode;
import ai.opsmind.toolgateway.domain.ToolDeniedException;
import ai.opsmind.toolgateway.domain.ToolExecutionRequest;

public final class ToolManifestRegistry {

    private final Map<String, ToolManifest> manifests;

    public ToolManifestRegistry(Collection<ToolManifest> manifests) {
        Map<String, ToolManifest> indexed = new HashMap<>();
        for (ToolManifest manifest : manifests) {
            if (indexed.putIfAbsent(manifest.registryKey(), manifest) != null) {
                throw new IllegalStateException("Duplicate tool manifest: " + manifest.registryKey());
            }
        }
        this.manifests = Map.copyOf(indexed);
    }

    public ToolManifest require(ToolExecutionRequest request) {
        String key = String.valueOf(request.tool()) + ":" + request.action() + ":"
            + request.schemaVersion();
        ToolManifest manifest = manifests.get(key);
        if (manifest == null) {
            throw new ToolDeniedException(DenialCode.UNKNOWN_ACTION, "Tool action is not registered.");
        }
        if (!manifest.enabled()) {
            throw new ToolDeniedException(DenialCode.ACTION_DISABLED, "Tool action is disabled.");
        }
        return manifest;
    }

    public boolean hasEnabledActions() {
        return manifests.values().stream().anyMatch(ToolManifest::enabled);
    }

}
