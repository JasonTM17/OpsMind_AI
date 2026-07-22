package ai.opsmind.platform.audit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;

import java.time.Instant;
import java.util.UUID;

import ai.opsmind.platform.common.api.PlatformProblemException;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.support.TransactionSynchronizationManager;

class TransactionalAuditRepositoryTest {

    private final JdbcTemplate jdbcTemplate = mock(JdbcTemplate.class);
    private final TransactionalAuditRepository repository = new TransactionalAuditRepository(jdbcTemplate);

    @AfterEach
    void clearTransactionMarker() {
        TransactionSynchronizationManager.setActualTransactionActive(false);
    }

    @Test
    void insertsOnlyUntrustedEventFieldsAndLeavesChainToDatabaseTrigger() {
        TransactionSynchronizationManager.setActualTransactionActive(true);
        AuditEvent event = event();

        repository.append(event);

        ArgumentCaptor<String> sql = ArgumentCaptor.forClass(String.class);
        verify(jdbcTemplate).update(sql.capture(), any(Object[].class));
        assertThat(sql.getValue()).contains("INSERT INTO audit_events");
        assertThat(sql.getValue()).contains("correlation_id", "payload", "schema_version");
        assertThat(sql.getValue()).doesNotContain(
            "tenant_sequence_no", "previous_digest", "event_digest", "sequence_no"
        );
    }

    @Test
    void rejectsAppendOutsideTransactionAndMapsDatabaseRejectionSafely() {
        assertThatThrownBy(() -> repository.append(event()))
            .isInstanceOf(IllegalStateException.class);

        TransactionSynchronizationManager.setActualTransactionActive(true);
        org.mockito.Mockito.doThrow(new DataIntegrityViolationException("rejected"))
            .when(jdbcTemplate).update(anyString(), any(Object[].class));
        assertThatThrownBy(() -> repository.append(event()))
            .isInstanceOfSatisfying(PlatformProblemException.class, exception ->
                assertThat(exception.code()).isEqualTo("audit.persistence-rejected"));
    }

    private AuditEvent event() {
        return new AuditEvent(
            UUID.fromString("11111111-1111-4111-8111-111111111111"),
            UUID.fromString("22222222-2222-4222-8222-222222222222"),
            UUID.fromString("33333333-3333-4333-8333-333333333333"),
            "INCIDENT_CREATED",
            AuditEvent.INCIDENT_SCHEMA_VERSION,
            "incident",
            "44444444-4444-4444-8444-444444444444",
            UUID.fromString("55555555-5555-4555-8555-555555555555"),
            Instant.parse("2030-01-01T00:00:00Z"),
            "{\"eventType\":\"INCIDENT_CREATED\"}"
        );
    }
}
