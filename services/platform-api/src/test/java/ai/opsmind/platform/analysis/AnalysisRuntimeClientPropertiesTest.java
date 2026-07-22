package ai.opsmind.platform.analysis;

import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.net.URI;
import java.time.Duration;

import org.junit.jupiter.api.Test;

class AnalysisRuntimeClientPropertiesTest {

    @Test
    void acceptsExactHttpsAndLoopbackAnalysisEndpoints() {
        properties("https://ai-runtime.example.test/api/v1/analysis").validateEnabled();
        properties("http://127.0.0.1:8000/api/v1/analysis").validateEnabled();
        properties("http://ai-runtime:8000/api/v1/analysis", true).validateEnabled();
    }

    @Test
    void rejectsPlaceholderCleartextRemoteAndWrongPathEndpoints() {
        assertThatThrownBy(() -> properties(
            "https://ai-runtime.invalid.example/api/v1/analysis"
        ).validateEnabled()).isInstanceOf(IllegalStateException.class);
        assertThatThrownBy(() -> properties(
            "http://ai-runtime.example.test/api/v1/analysis"
        ).validateEnabled()).isInstanceOf(IllegalStateException.class);
        assertThatThrownBy(() -> properties(
            "https://ai-runtime.example.test/redirect"
        ).validateEnabled()).isInstanceOf(IllegalStateException.class);
    }

    private AnalysisRuntimeClientProperties properties(String endpoint) {
        return properties(endpoint, false);
    }

    private AnalysisRuntimeClientProperties properties(String endpoint, boolean allowLocalCleartext) {
        return new AnalysisRuntimeClientProperties(
            true,
            URI.create(endpoint),
            allowLocalCleartext,
            Duration.ofSeconds(1),
            Duration.ofSeconds(10),
            65_536
        );
    }
}
