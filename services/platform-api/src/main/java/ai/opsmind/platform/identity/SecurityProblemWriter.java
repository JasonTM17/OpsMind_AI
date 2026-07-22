package ai.opsmind.platform.identity;

import java.io.IOException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;

import ai.opsmind.platform.common.api.PlatformProblemWriter;

import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;

@Component
public final class SecurityProblemWriter {

    private final PlatformProblemWriter problemWriter;

    public SecurityProblemWriter(PlatformProblemWriter problemWriter) {
        this.problemWriter = problemWriter;
    }

    public void write(
        HttpServletRequest request,
        HttpServletResponse response,
        HttpStatus status,
        String code,
        String safeDetail
    ) throws IOException {
        problemWriter.write(request, response, status, code, safeDetail);
    }
}
