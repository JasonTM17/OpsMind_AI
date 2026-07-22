package ai.opsmind.platform.common.api;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.nio.charset.StandardCharsets;

import org.junit.jupiter.api.Test;

class IdempotencyAndConcurrencyTest {

    @Test
    void validatesOpaqueIdempotencyKeysWithoutNormalizingAliases() {
        assertThat(IdempotencyKey.parse("request-01").value()).isEqualTo("request-01");
        assertThatThrownBy(() -> IdempotencyKey.parse(" request-01"))
            .isInstanceOfSatisfying(PlatformProblemException.class, exception ->
                assertThat(exception.code()).isEqualTo("request.idempotency-key-invalid"));
        assertThatThrownBy(() -> IdempotencyKey.parse("x".repeat(129)))
            .isInstanceOf(PlatformProblemException.class);
    }

    @Test
    void computesAndComparesRequestDigestsDefensively() {
        byte[] digest = RequestDigest.sha256("payload".getBytes(StandardCharsets.UTF_8));
        byte[] copy = RequestDigest.copyAndValidate(digest);
        copy[0] ^= 1;

        assertThat(RequestDigest.constantTimeEquals(digest, copy)).isFalse();
        assertThat(RequestDigest.constantTimeEquals(digest, RequestDigest.sha256(
            "payload".getBytes(StandardCharsets.UTF_8)
        ))).isTrue();
    }

    @Test
    void enforcesStrongIfMatchAndMapsVersionConflicts() {
        assertThat(OptimisticConcurrency.requireIfMatch("\"7\"")).isEqualTo(7);
        assertThat(OptimisticConcurrency.etag(8)).isEqualTo("\"8\"");
        assertThatThrownBy(() -> OptimisticConcurrency.requireIfMatch("W/\"7\""))
            .isInstanceOfSatisfying(PlatformProblemException.class, exception ->
                assertThat(exception.code()).isEqualTo("request.if-match-invalid"));
        assertThatThrownBy(() -> OptimisticConcurrency.requireExactlyOneUpdated(0))
            .isInstanceOfSatisfying(PlatformProblemException.class, exception ->
                assertThat(exception.code()).isEqualTo("resource.version-conflict"));
        assertThatThrownBy(() -> OptimisticConcurrency.requireCurrentVersion(3, 2))
            .isInstanceOfSatisfying(PlatformProblemException.class, exception -> {
                assertThat(exception.status()).isEqualTo(org.springframework.http.HttpStatus.PRECONDITION_FAILED);
                assertThat(exception.code()).isEqualTo("request.if-match-stale");
            });
    }
}
