package ai.opsmind.platform.incident;

import java.nio.charset.StandardCharsets;
import java.util.UUID;

import ai.opsmind.platform.common.api.RequestDigest;

final class IncidentRequestIdentity {

    private static final String VERSION = "incident-command-v1";

    private IncidentRequestIdentity() {
    }

    static byte[] create(
        UUID actorId,
        UUID organizationId,
        UUID projectId,
        CreateIncidentRequest request
    ) {
        CanonicalFields fields = base(
            actorId,
            "POST",
            incidentCollectionPath(organizationId, projectId),
            null
        );
        fields.add("title", normalize(request.title()));
        fields.add("summary", normalize(request.summary()));
        fields.add("severity", request.severity().name());
        fields.add("reason", normalize(request.reason()));
        return fields.digest();
    }

    static byte[] transition(
        UUID actorId,
        UUID organizationId,
        UUID projectId,
        UUID incidentId,
        long expectedVersion,
        TransitionIncidentRequest request
    ) {
        CanonicalFields fields = base(
            actorId,
            "POST",
            incidentCollectionPath(organizationId, projectId)
                + "/" + incidentId + "/transitions",
            expectedVersion
        );
        fields.add("targetStatus", request.targetStatus().name());
        fields.add("reason", normalize(request.reason()));
        fields.add("rootCause", normalizeOptional(request.rootCause()));
        fields.add("resolutionSummary", normalizeOptional(request.resolutionSummary()));
        return fields.digest();
    }

    static String incidentCollectionPath(UUID organizationId, UUID projectId) {
        return "/api/v1/organizations/" + organizationId + "/projects/" + projectId + "/incidents";
    }

    static String normalize(String value) {
        return value == null ? null : value.trim();
    }

    static String normalizeOptional(String value) {
        return value == null || value.isBlank() ? null : value.trim();
    }

    private static CanonicalFields base(UUID actorId, String method, String path, Long expectedVersion) {
        CanonicalFields fields = new CanonicalFields();
        fields.add("schemaVersion", VERSION);
        fields.add("actorId", actorId.toString());
        fields.add("method", method);
        fields.add("path", path);
        fields.add("expectedVersion", expectedVersion == null ? null : expectedVersion.toString());
        return fields;
    }

    private static final class CanonicalFields {

        private final StringBuilder value = new StringBuilder();

        void add(String name, String fieldValue) {
            value.append(name.length()).append(':').append(name).append('=');
            if (fieldValue == null) {
                value.append("-1:");
            }
            else {
                value.append(fieldValue.length()).append(':').append(fieldValue);
            }
            value.append('\n');
        }

        byte[] digest() {
            return RequestDigest.sha256(value.toString().getBytes(StandardCharsets.UTF_8));
        }
    }
}
