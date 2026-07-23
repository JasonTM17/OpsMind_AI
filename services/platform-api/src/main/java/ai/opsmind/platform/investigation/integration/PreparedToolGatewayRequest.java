package ai.opsmind.platform.investigation.integration;

import java.time.Instant;
import java.util.UUID;
import java.util.regex.Pattern;

record PreparedToolGatewayRequest(
    UUID intentId,
    UUID executionId,
    UUID evidenceId,
    InvestigationToolInvocation invocation,
    String actorSubject,
    Instant deadlineAt,
    byte[] body,
    String requestDigest
) {
    private static final Pattern DIGEST = Pattern.compile("[0-9a-f]{64}");

    PreparedToolGatewayRequest {
        body = body == null ? null : body.clone();
        if (intentId == null || executionId == null || evidenceId == null || invocation == null
            || actorSubject == null || actorSubject.isBlank() || deadlineAt == null
            || body == null || body.length == 0 || body.length > 65_536
            || requestDigest == null || !DIGEST.matcher(requestDigest).matches()) {
            throw new IllegalArgumentException("Prepared Tool Gateway request is invalid.");
        }
    }

    @Override
    public byte[] body() {
        return body.clone();
    }
}
