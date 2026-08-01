package ai.opsmind.platform.common.api;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;
import org.springframework.http.HttpStatus;
import org.springframework.mock.web.MockHttpServletRequest;

@ExtendWith(OutputCaptureExtension.class)
class PlatformExceptionHandlerTest {

    private static final String SENSITIVE_CAUSE_DETAIL = "sensitive-sql-provider-detail";

    @Test
    void classifiedFailureLogsTaxonomyWithoutRenderingTheCause(CapturedOutput output) {
        MockHttpServletRequest request = new MockHttpServletRequest();
        request.setAttribute(CorrelationIdFilter.ATTRIBUTE_NAME, "trace-safe-123");
        PlatformProblemException exception = new PlatformProblemException(
            HttpStatus.SERVICE_UNAVAILABLE,
            "evidence-artifact.persistence-unavailable",
            "Artifact metadata persistence is temporarily unavailable.",
            new IllegalStateException(SENSITIVE_CAUSE_DETAIL)
        );
        exception.addSuppressed(new IllegalStateException("sensitive-suppressed-detail"));

        new PlatformExceptionHandler().handlePlatformProblem(exception, request);

        assertThat(output)
            .contains("traceId=trace-safe-123")
            .contains("code=evidence-artifact.persistence-unavailable")
            .contains("causeType=java.lang.IllegalStateException")
            .doesNotContain(SENSITIVE_CAUSE_DETAIL, "sensitive-suppressed-detail");
        assertThat(exception.getCause()).isNotNull();
    }
}
