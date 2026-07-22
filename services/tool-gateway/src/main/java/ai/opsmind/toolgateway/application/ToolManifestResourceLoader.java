package ai.opsmind.toolgateway.application;

import java.io.IOException;
import java.time.Duration;
import java.util.List;
import java.util.Set;

import com.fasterxml.jackson.annotation.JsonProperty;

import org.springframework.core.io.ClassPathResource;

import tools.jackson.databind.ObjectMapper;

/** Loads the authoritative fixture manifest and fails startup on drift or unsafe declarations. */
public final class ToolManifestResourceLoader {

    private static final String FIXTURE_MANIFEST =
        "tool-manifests/observability-metrics-query-v1.json";
    private static final String FIXTURE_SCHEMA_ID =
        "https://contracts.opsmind.invalid/tool-gateway/v1/tool-execution-request.schema.json";

    private final ObjectMapper objectMapper;

    public ToolManifestResourceLoader(ObjectMapper objectMapper) {
        this.objectMapper = objectMapper;
    }

    public ToolManifestRegistry loadFixtureRegistry() {
        ManifestDocument document;
        try (var input = new ClassPathResource(FIXTURE_MANIFEST).getInputStream()) {
            document = objectMapper.readValue(input, ManifestDocument.class);
        }
        catch (IOException exception) {
            throw new IllegalStateException("Tool manifest cannot be loaded safely.", exception);
        }
        if (document == null || !Set.of("fixture").equals(document.enabledProfiles())
            || !FIXTURE_SCHEMA_ID.equals(document.requestSchemaId())
            || !"fixture-read-only".equals(document.credentialProfile())
            || !Set.of("fixture://observability").equals(document.egressTargets())) {
            throw new IllegalStateException("Fixture manifest profile declaration is unsafe.");
        }
        return new ToolManifestRegistry(List.of(document.toManifest()));
    }

    private record ManifestDocument(
        String tool,
        String action,
        @JsonProperty("schema_version") String schemaVersion,
        @JsonProperty("manifest_version") String manifestVersion,
        @JsonProperty("connector_id") String connectorId,
        @JsonProperty("enabled_profiles") Set<String> enabledProfiles,
        @JsonProperty("read_only") boolean readOnly,
        @JsonProperty("request_schema_id") String requestSchemaId,
        @JsonProperty("risk_class") String riskClass,
        @JsonProperty("required_role") String requiredRole,
        @JsonProperty("resource_prefix") String resourcePrefix,
        @JsonProperty("credential_profile") String credentialProfile,
        @JsonProperty("timeout_ms") long timeoutMilliseconds,
        @JsonProperty("maximum_bytes") int maximumBytes,
        @JsonProperty("maximum_items") int maximumItems,
        @JsonProperty("allowed_arguments") Set<String> allowedArguments,
        @JsonProperty("egress_targets") Set<String> egressTargets,
        @JsonProperty("redaction_class") String redactionClass,
        @JsonProperty("audit_class") String auditClass
    ) {
        private ToolManifest toManifest() {
            return new ToolManifest(
                tool, action, schemaVersion, manifestVersion, connectorId, true, readOnly,
                requestSchemaId, riskClass, requiredRole, resourcePrefix, credentialProfile,
                Duration.ofMillis(timeoutMilliseconds), maximumBytes, maximumItems,
                allowedArguments, egressTargets, redactionClass, auditClass
            );
        }
    }
}
