package ai.opsmind.platform.persistence;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.nio.charset.StandardCharsets;
import java.util.UUID;

import ai.opsmind.platform.common.api.IdempotencyKey;
import ai.opsmind.platform.common.api.PlatformProblemException;
import ai.opsmind.platform.common.api.RequestDigest;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.dao.DataAccessResourceFailureException;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.support.TransactionSynchronizationManager;

class TransactionalIdempotencyRepositoryTest {

    private static final UUID ORGANIZATION_ID = UUID.fromString("11111111-1111-4111-8111-111111111111");
    private static final UUID ACTOR_ID = UUID.fromString("22222222-2222-4222-8222-222222222222");
    private static final byte[] DIGEST = RequestDigest.sha256(
        "request".getBytes(StandardCharsets.UTF_8)
    );

    private final JdbcTemplate jdbcTemplate = mock(JdbcTemplate.class);
    private final TransactionalIdempotencyRepository repository =
        new TransactionalIdempotencyRepository(jdbcTemplate);

    @AfterEach
    void clearTransactionMarker() {
        TransactionSynchronizationManager.setActualTransactionActive(false);
    }

    @Test
    void claimMapsGenericDatabaseFailureToServiceUnavailable() {
        TransactionSynchronizationManager.setActualTransactionActive(true);
        when(jdbcTemplate.update(anyString(), any(Object[].class)))
            .thenThrow(new DataAccessResourceFailureException("offline"));

        assertUnavailable(() -> repository.claim(
            ORGANIZATION_ID, ACTOR_ID, new IdempotencyKey("claim-failure"), DIGEST
        ));
    }

    @Test
    void completeMapsGenericDatabaseFailureToServiceUnavailable() {
        TransactionSynchronizationManager.setActualTransactionActive(true);
        when(jdbcTemplate.update(anyString(), any(Object[].class)))
            .thenThrow(new DataAccessResourceFailureException("offline"));

        assertUnavailable(() -> repository.complete(
            ORGANIZATION_ID,
            ACTOR_ID,
            new IdempotencyKey("complete-failure"),
            DIGEST,
            201,
            "{\"status\":\"OPEN\"}"
        ));
    }

    private void assertUnavailable(org.assertj.core.api.ThrowableAssert.ThrowingCallable action) {
        assertThatThrownBy(action)
            .isInstanceOfSatisfying(PlatformProblemException.class, exception -> {
                assertThat(exception.status()).isEqualTo(HttpStatus.SERVICE_UNAVAILABLE);
                assertThat(exception.code()).isEqualTo("idempotency.persistence-unavailable");
            });
    }
}
