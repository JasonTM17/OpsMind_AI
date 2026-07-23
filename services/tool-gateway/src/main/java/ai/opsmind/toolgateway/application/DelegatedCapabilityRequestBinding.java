package ai.opsmind.toolgateway.application;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.Set;
import java.util.regex.Pattern;

import ai.opsmind.toolgateway.domain.DenialCode;
import ai.opsmind.toolgateway.domain.ToolDeniedException;
import ai.opsmind.toolgateway.domain.ToolExecutionRequest;

import org.springframework.security.oauth2.jwt.Jwt;

/** Verifies that one delegated capability authorizes these exact canonical request bytes. */
final class DelegatedCapabilityRequestBinding {

    private static final Pattern DIGEST = Pattern.compile("[0-9a-f]{64}");

    private final RequestDigester requestDigester;

    DelegatedCapabilityRequestBinding(RequestDigester requestDigester) {
        this.requestDigester = requestDigester;
    }

    void validate(VerifiedCapability capability, Jwt jwt, ToolExecutionRequest request) {
        if (!capability.subject().equals(request.actorSubject())
            || !capability.tenantId().equals(request.tenantId())
            || !capability.projectId().equals(request.projectId())
            || !capability.incidentId().equals(request.incidentId())
            || !capability.runId().equals(request.runId())
            || !capability.actions().equals(Set.of(canonicalAction(request)))
            || !capability.resources().equals(Set.of(request.resource()))
            || request.resultBudget() == null
            || request.resultBudget().maxBytes() > capability.maximumBytes()) {
            throw mismatch("Request scope does not match the delegated capability.");
        }
        Object value = jwt.getClaims().get("request_digest");
        String claimed = value instanceof String string ? string : "";
        String actual = requestDigester.digest(request);
        if (!DIGEST.matcher(claimed).matches() || !MessageDigest.isEqual(
            claimed.getBytes(StandardCharsets.US_ASCII),
            actual.getBytes(StandardCharsets.US_ASCII)
        )) {
            throw mismatch("Request body does not match the delegated capability.");
        }
    }

    private String canonicalAction(ToolExecutionRequest request) {
        return request.tool() + ":" + request.action() + ":" + request.schemaVersion();
    }

    private ToolDeniedException mismatch(String message) {
        return new ToolDeniedException(DenialCode.CAPABILITY_SCOPE_MISMATCH, message);
    }
}
