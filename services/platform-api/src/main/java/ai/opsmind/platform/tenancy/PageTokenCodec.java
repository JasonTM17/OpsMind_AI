package ai.opsmind.platform.tenancy;

import java.nio.charset.StandardCharsets;
import java.util.Base64;
import java.util.UUID;

import ai.opsmind.platform.common.api.PlatformProblemException;

import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;

@Component
public final class PageTokenCodec {

    private static final String VERSION_PREFIX = "v1:";

    public UUID decode(String token) {
        if (token == null || token.isBlank()) {
            return null;
        }
        if (token.length() > 512) {
            throw invalidToken();
        }
        try {
            String decoded = new String(Base64.getUrlDecoder().decode(token), StandardCharsets.UTF_8);
            if (!decoded.startsWith(VERSION_PREFIX)) {
                throw invalidToken();
            }
            return UUID.fromString(decoded.substring(VERSION_PREFIX.length()));
        }
        catch (IllegalArgumentException exception) {
            throw invalidToken();
        }
    }

    public String encode(UUID value) {
        String payload = VERSION_PREFIX + value;
        return Base64.getUrlEncoder().withoutPadding().encodeToString(payload.getBytes(StandardCharsets.UTF_8));
    }

    private PlatformProblemException invalidToken() {
        return new PlatformProblemException(
            HttpStatus.BAD_REQUEST,
            "pagination.invalid-token",
            "The page token is invalid or expired."
        );
    }
}
