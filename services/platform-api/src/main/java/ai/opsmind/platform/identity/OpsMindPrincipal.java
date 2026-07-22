package ai.opsmind.platform.identity;

import java.net.URI;
import java.util.LinkedHashSet;
import java.util.Set;

public record OpsMindPrincipal(
    URI issuer,
    String subject,
    String displayName,
    String email,
    Set<String> scopes
) {
    private static final int MAX_SCOPES = 100;
    private static final int MAX_SCOPE_LENGTH = 128;

    public OpsMindPrincipal {
        if (issuer == null || !issuer.isAbsolute()
            || !"https".equalsIgnoreCase(issuer.getScheme())
            || issuer.getHost() == null
            || issuer.getRawUserInfo() != null
            || issuer.getRawQuery() != null
            || issuer.getRawFragment() != null) {
            throw new IllegalArgumentException("Verified principal issuer must be an absolute HTTPS URI.");
        }
        if (subject == null || subject.isBlank() || subject.length() > 255) {
            throw new IllegalArgumentException("Verified principal subject is invalid.");
        }
        displayName = normalizeOptional(displayName, 255);
        email = normalizeOptional(email, 320);
        scopes = normalizeScopes(scopes);
    }

    private static String normalizeOptional(String value, int maximumLength) {
        if (value == null || value.isBlank()) {
            return null;
        }
        String normalized = value.trim();
        if (normalized.length() > maximumLength) {
            throw new IllegalArgumentException("Verified principal claim exceeds its contract limit.");
        }
        return normalized;
    }

    private static Set<String> normalizeScopes(Set<String> values) {
        if (values == null || values.isEmpty()) {
            return Set.of();
        }
        if (values.size() > MAX_SCOPES) {
            throw new IllegalArgumentException("Verified principal contains too many scopes.");
        }
        Set<String> normalized = new LinkedHashSet<>();
        for (String value : values) {
            if (value == null || value.isBlank() || value.length() > MAX_SCOPE_LENGTH) {
                throw new IllegalArgumentException("Verified principal scope is invalid.");
            }
            normalized.add(value.trim());
        }
        return Set.copyOf(normalized);
    }
}
