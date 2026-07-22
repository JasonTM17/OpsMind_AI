package ai.opsmind.toolgateway.api;

import static org.assertj.core.api.Assertions.assertThat;

import java.net.URI;
import java.time.Duration;

import ai.opsmind.toolgateway.config.GatewaySettings;

import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockFilterChain;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;

import tools.jackson.databind.json.JsonMapper;

class JsonRequestBodyLimitFilterTest {

    @Test
    void rejectsOversizedChunkedBodyBeforeJsonParsing() throws Exception {
        GatewaySettings settings = new GatewaySettings(
            URI.create("https://platform.invalid.example"),
            "opsmind-tool-gateway",
            "opsmind-platform-api",
            null,
            URI.create("https://platform.invalid.example"),
            "opsmind-tool-gateway-workload",
            null,
            Duration.ofMinutes(5),
            1_024,
            262_144
        );
        GatewayProblemWriter writer = new GatewayProblemWriter(JsonMapper.builder().build());
        JsonRequestBodyLimitFilter filter = new JsonRequestBodyLimitFilter(writer, settings);
        MockHttpServletRequest request = new MockHttpServletRequest(
            "POST",
            "/internal/v1/tools/execute"
        );
        request.setServletPath("/internal/v1/tools/execute");
        request.setContentType("application/json");
        request.setContent("x".repeat(1_025).getBytes(java.nio.charset.StandardCharsets.UTF_8));
        request.removeHeader("Content-Length");
        MockHttpServletResponse response = new MockHttpServletResponse();

        filter.doFilter(request, response, new MockFilterChain());

        assertThat(response.getStatus()).isEqualTo(413);
        assertThat(response.getContentAsString())
            .contains("\"code\":\"request.oversize\"")
            .doesNotContain("x".repeat(20));
    }
}
