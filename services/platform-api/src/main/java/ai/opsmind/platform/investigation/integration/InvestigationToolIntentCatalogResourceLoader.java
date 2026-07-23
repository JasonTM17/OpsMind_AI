package ai.opsmind.platform.investigation.integration;

import java.io.IOException;
import java.io.InputStream;
import java.util.Arrays;
import java.util.HexFormat;
import java.util.Map;
import java.util.TreeMap;

import ai.opsmind.platform.common.api.RequestDigest;

import com.fasterxml.jackson.annotation.JsonProperty;
import org.springframework.core.io.ClassPathResource;

import tools.jackson.core.JacksonException;
import tools.jackson.core.StreamReadFeature;
import tools.jackson.databind.DeserializationFeature;
import tools.jackson.databind.ObjectMapper;

final class InvestigationToolIntentCatalogResourceLoader {

    private static final String RESOURCE =
        "investigation-intents/prometheus-synthetic-latency-v1.json";
    private static final int MAXIMUM_RESOURCE_BYTES = 16_384;

    private InvestigationToolIntentCatalogResourceLoader() { }

    static InvestigationToolIntentCatalog load(ObjectMapper objectMapper) {
        try {
            byte[] bytes;
            try (InputStream input = new ClassPathResource(RESOURCE).getInputStream()) {
                bytes = input.readNBytes(MAXIMUM_RESOURCE_BYTES + 1);
            }
            if (bytes.length > MAXIMUM_RESOURCE_BYTES) {
                throw new IllegalStateException("Investigation intent catalog resource is oversized.");
            }
            Definition[] definitions = objectMapper.readerFor(Definition[].class)
                .with(StreamReadFeature.STRICT_DUPLICATE_DETECTION)
                .with(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES)
                .with(DeserializationFeature.FAIL_ON_TRAILING_TOKENS)
                .readValue(bytes);
            return new InvestigationToolIntentCatalog(
                Arrays.stream(definitions).map(value -> invocation(objectMapper, value)).toList()
            );
        }
        catch (IOException exception) {
            throw new IllegalStateException("Investigation intent catalog cannot be loaded.", exception);
        }
    }

    private static InvestigationToolInvocation invocation(
        ObjectMapper objectMapper,
        Definition value
    ) {
        Map<String, Object> arguments = value.arguments() == null
            ? Map.of() : Map.copyOf(value.arguments());
        try {
            String digest = "sha256:" + HexFormat.of().formatHex(RequestDigest.sha256(
                objectMapper.writeValueAsBytes(new TreeMap<>(arguments))
            ));
            return new InvestigationToolInvocation(
                value.connector(), value.operation(), digest, value.tool(), value.action(),
                value.schemaVersion(), value.resource(), arguments, value.maximumBytes(),
                value.maximumItems(), value.requiredRole(), value.policyVersion(),
                value.expectedManifestVersion()
            );
        }
        catch (JacksonException exception) {
            throw new IllegalStateException("Investigation intent arguments are not canonical.", exception);
        }
    }

    private record Definition(
        String connector,
        String operation,
        String tool,
        String action,
        @JsonProperty("schema_version") String schemaVersion,
        String resource,
        Map<String, Object> arguments,
        @JsonProperty("maximum_bytes") int maximumBytes,
        @JsonProperty("maximum_items") int maximumItems,
        @JsonProperty("required_role") String requiredRole,
        @JsonProperty("policy_version") String policyVersion,
        @JsonProperty("expected_manifest_version") String expectedManifestVersion
    ) { }
}
