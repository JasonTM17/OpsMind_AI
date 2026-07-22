package ai.opsmind.platform.common.api;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;

import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;

class JsonRequestBodyLimitFilterTest {

    @Test
    void appliesToApiPathBelowServletContextPrefix() {
        var request = new MockHttpServletRequest("POST", "/opsmind/api/v1/incidents");
        request.setContextPath("/opsmind");
        request.setServletPath("/api/v1/incidents");
        request.setContentType("application/json");
        var filter = new JsonRequestBodyLimitFilter(mock(PlatformProblemWriter.class), 1_024);

        assertThat(filter.shouldNotFilter(request)).isFalse();
    }
}
