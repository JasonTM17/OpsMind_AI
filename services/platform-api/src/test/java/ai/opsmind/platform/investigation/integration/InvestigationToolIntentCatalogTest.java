package ai.opsmind.platform.investigation.integration;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.List;
import java.util.Map;
import java.util.UUID;

import ai.opsmind.platform.analysis.AnalysisRuntimeResponse;

import org.junit.jupiter.api.Test;

import tools.jackson.databind.ObjectMapper;

class InvestigationToolIntentCatalogTest {

    private static final String ARGUMENTS_DIGEST =
        "sha256:51ef8c2e4e2926e103ddd877490c64d604b1df593ca23cffca8d1b2fac5d8700";

    @Test
    void loadsPublishedSelectorAndResolvesOnlyItsServerOwnedTemplate() {
        InvestigationToolIntentCatalog catalog =
            InvestigationToolIntentCatalogResourceLoader.load(new ObjectMapper());
        assertThat(catalog.publicSelectors()).containsExactly(
            new InvestigationToolIntentCatalog.Selector("metrics", "query", ARGUMENTS_DIGEST)
        );

        InvestigationToolInvocation invocation = catalog.resolve(intent(ARGUMENTS_DIGEST));
        assertThat(invocation.canonicalAction()).isEqualTo("observability:metrics.query:1.0");
        assertThat(invocation.resource()).isEqualTo("prometheus:synthetic/opsmind-api");
        assertThat(invocation.arguments()).containsExactlyInAnyOrderEntriesOf(Map.of(
            "service", "opsmind-api",
            "metric", "http_request_duration_seconds",
            "max_points", 3
        ));
        assertThatThrownBy(() -> invocation.arguments().put("metric", "untrusted"))
            .isInstanceOf(UnsupportedOperationException.class);
    }

    @Test
    void rejectsUnknownOrMutatedModelSelectors() {
        InvestigationToolIntentCatalog catalog =
            InvestigationToolIntentCatalogResourceLoader.load(new ObjectMapper());
        assertThatThrownBy(() -> catalog.resolve(intent("sha256:" + "0".repeat(64))))
            .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("allowlisted");
        assertThatThrownBy(() -> catalog.resolve(new AnalysisRuntimeResponse.ToolIntent(
            UUID.randomUUID(), "logs", "query", ARGUMENTS_DIGEST, "Untrusted selector drift."
        ))).isInstanceOf(IllegalArgumentException.class).hasMessageContaining("allowlisted");
    }

    @Test
    void rejectsDuplicateCatalogSelectors() {
        InvestigationToolInvocation first = invocation("prometheus:synthetic/opsmind-api");
        InvestigationToolInvocation second = invocation("prometheus:synthetic/other");
        assertThatThrownBy(() -> new InvestigationToolIntentCatalog(List.of(first, second)))
            .isInstanceOf(IllegalStateException.class).hasMessageContaining("duplicate selector");
    }

    private AnalysisRuntimeResponse.ToolIntent intent(String digest) {
        return new AnalysisRuntimeResponse.ToolIntent(
            UUID.randomUUID(), "metrics", "query", digest, "Request the approved latency signal."
        );
    }

    private InvestigationToolInvocation invocation(String resource) {
        return new InvestigationToolInvocation(
            "metrics", "query", ARGUMENTS_DIGEST, "observability", "metrics.query", "1.0",
            resource, Map.of("service", "opsmind-api", "metric", "latency", "max_points", 3),
            65_536, 10, "operator:read", "policy-prometheus-read-v1",
            "observability.metrics.query@1"
        );
    }
}
