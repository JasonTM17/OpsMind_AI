package ai.opsmind.toolgateway.application;

import java.io.IOException;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Duration;
import java.util.HexFormat;
import java.util.List;
import java.util.Set;

import com.fasterxml.jackson.annotation.JsonProperty;

import org.springframework.core.io.ClassPathResource;

import tools.jackson.databind.ObjectMapper;

/** Loads the authoritative fixture manifest and fails startup on drift or unsafe declarations. */
public final class ToolManifestResourceLoader {

    private static final String FIXTURE_MANIFEST =
        "tool-manifests/observability-metrics-query-v1.json";
    private static final String PROMETHEUS_MANIFEST =
        "tool-manifests/observability-metrics-query-prometheus-v1.json";
    private static final String FIXTURE_SCHEMA_ID =
        "https://contracts.opsmind.invalid/tool-gateway/v1/tool-execution-request.schema.json";

    private final ObjectMapper objectMapper;

    public ToolManifestResourceLoader(ObjectMapper objectMapper) {
        this.objectMapper = objectMapper;
    }

    public ToolManifestRegistry loadFixtureRegistry() {
        LoadedManifest loaded = read(FIXTURE_MANIFEST);
        ManifestDocument document = loaded.document();
        if (!Set.of("fixture").equals(document.enabledProfiles())
            || !FIXTURE_SCHEMA_ID.equals(document.requestSchemaId())
            || !"fixture-read-only".equals(document.credentialProfile())
            || !Set.of("fixture://observability").equals(document.egressTargets())) {
            throw new IllegalStateException("Fixture manifest profile declaration is unsafe.");
        }
        return new ToolManifestRegistry(List.of(document.toManifest(
            document.egressTargets(), "fixture", loaded.byteDigest()
        )));
    }

    public ToolManifestRegistry loadPrometheusRegistry(String egressTarget) {
        LoadedManifest loaded = read(PROMETHEUS_MANIFEST);
        ManifestDocument document = loaded.document();
        if (!Set.of("prometheus").equals(document.enabledProfiles())
            || !FIXTURE_SCHEMA_ID.equals(document.requestSchemaId())
            || !"prometheus-read-only".equals(document.credentialProfile())
            || !"prometheus-read-only".equals(document.connectorId())
            || !Set.of("configured://prometheus-base-uri").equals(document.egressTargets())) {
            throw new IllegalStateException("Prometheus manifest profile declaration is unsafe.");
        }
        return new ToolManifestRegistry(List.of(
            document.toManifest(Set.of(egressTarget), "prometheus", loaded.byteDigest())
        ));
    }

    private LoadedManifest read(String resource) {
        ManifestDocument document;
        try (var input = new ClassPathResource(resource).getInputStream()) {
            byte[] manifestBytes = input.readAllBytes();
            document = objectMapper.readValue(manifestBytes, ManifestDocument.class);
            MessageDigest sha256 = MessageDigest.getInstance("SHA-256");
            String digest = "sha256:" + HexFormat.of().formatHex(sha256.digest(manifestBytes));
            if (document == null) {
                throw new IllegalStateException("Tool manifest document is empty.");
            }
            return new LoadedManifest(document, digest);
        }
        catch (IOException | NoSuchAlgorithmException exception) {
            throw new IllegalStateException("Tool manifest cannot be loaded safely.", exception);
        }
    }

    private record LoadedManifest(ManifestDocument document, String byteDigest) {}

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
        private ToolManifest toManifest(
            Set<String> effectiveEgressTargets,
            String connectorProfile,
            String manifestByteDigest
        ) {
            return new ToolManifest(
                tool, action, schemaVersion, manifestVersion, connectorId,
                connectorProfile, manifestByteDigest, true, readOnly, requestSchemaId,
                riskClass, requiredRole, resourcePrefix, credentialProfile,
                Duration.ofMillis(timeoutMilliseconds), maximumBytes, maximumItems,
                allowedArguments, effectiveEgressTargets, redactionClass, auditClass
            );
        }
    }
}
