package ai.opsmind.platform.identity;

import java.net.URI;
import java.net.URL;
import java.util.Arrays;
import java.util.Collection;
import java.util.LinkedHashSet;
import java.util.Set;

import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.stereotype.Component;

@Component
public final class JwtPrincipalMapper {

    private static final int MAX_SCOPES = 100;
    private static final int MAX_SCOPE_LENGTH = 128;

    public OpsMindPrincipal map(Jwt jwt) {
        if (jwt == null) {
            throw new IllegalArgumentException("Verified JWT is required.");
        }
        URI issuer = parseIssuer(jwt.getIssuer());
        String subject = jwt.getSubject();
        Set<String> scopes = new LinkedHashSet<>();
        addSpaceSeparated(scopes, jwt.getClaimAsString("scope"));
        addClaimCollection(scopes, jwt.getClaims().get("scp"));

        return new OpsMindPrincipal(
            issuer,
            subject,
            jwt.getClaimAsString("name"),
            jwt.getClaimAsString("email"),
            scopes
        );
    }

    private URI parseIssuer(URL issuerUrl) {
        URI issuer;
        try {
            issuer = issuerUrl == null ? null : URI.create(issuerUrl.toString());
        }
        catch (IllegalArgumentException exception) {
            throw new IllegalArgumentException("Verified principal issuer must be an absolute HTTPS URI.");
        }
        if (issuer == null || !issuer.isAbsolute()
            || !"https".equalsIgnoreCase(issuer.getScheme()) || issuer.getHost() == null
            || issuer.getRawUserInfo() != null || issuer.getRawQuery() != null
            || issuer.getRawFragment() != null) {
            throw new IllegalArgumentException("Verified principal issuer must be an absolute HTTPS URI.");
        }
        return issuer;
    }

    private void addSpaceSeparated(Set<String> scopes, String value) {
        if (value == null || value.isBlank()) {
            return;
        }
        Arrays.stream(value.trim().split("\\s+"))
            .filter(scope -> !scope.isBlank())
            .forEach(scope -> addScope(scopes, scope));
    }

    private void addClaimCollection(Set<String> scopes, Object claim) {
        if (claim instanceof Collection<?> values) {
            if (values.size() > MAX_SCOPES) {
                throw new IllegalArgumentException("Verified principal contains too many scopes.");
            }
            values.forEach(value -> {
                if (!(value instanceof String scope)) {
                    throw new IllegalArgumentException("Verified principal scope claim is invalid.");
                }
                addScope(scopes, scope);
            });
        }
        else if (claim instanceof String value) {
            addSpaceSeparated(scopes, value);
        }
    }

    private void addScope(Set<String> scopes, String scope) {
        String normalized = scope.trim();
        if (normalized.isBlank() || normalized.length() > MAX_SCOPE_LENGTH) {
            throw new IllegalArgumentException("Verified principal scope is invalid.");
        }
        if (scopes.size() >= MAX_SCOPES && !scopes.contains(normalized)) {
            throw new IllegalArgumentException("Verified principal contains too many scopes.");
        }
        scopes.add(normalized);
    }
}
