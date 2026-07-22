package ai.opsmind.platform.messaging;

import static ai.opsmind.platform.testing.PostgresTenantFixtures.TENANT_A;
import static ai.opsmind.platform.testing.PostgresTenantFixtures.USER_A;

import java.nio.charset.StandardCharsets;
import java.sql.SQLException;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Objects;
import java.util.UUID;
import java.util.function.Supplier;

import ai.opsmind.platform.common.api.RequestDigest;
import ai.opsmind.platform.tenancy.TenantContextSql;
import ai.opsmind.platform.testing.PostgresIntegrationEnvironment;
import ai.opsmind.platform.testing.PostgresRoleDataSource;
import ai.opsmind.platform.testing.PostgresTenantFixtures;

import com.zaxxer.hikari.HikariDataSource;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.support.TransactionTemplate;

import tools.jackson.databind.ObjectMapper;

final class MessagingPostgresTestContext implements AutoCloseable {

    private static final Duration LEASE_DURATION = Duration.ofSeconds(5);

    private final HikariDataSource appDataSource;
    private final HikariDataSource dispatcherDataSource;
    private final JdbcTemplate appJdbc;
    private final JdbcTemplate dispatcherJdbc;
    private final TransactionTemplate appTransactions;
    private final TransactionTemplate dispatcherTransactions;
    private final TenantContextSql appTenantContext;
    private final OutboxDispatcherTenantContextSql dispatcherTenantContext;
    private final OutboxDispatcherTenantScheduler dispatcherScheduler;
    private final TransactionalOutboxRepository outbox;
    private final TransactionalOutboxLeaseStore leases;
    private final TransactionalInboxRepository inbox;

    private MessagingPostgresTestContext(
        HikariDataSource appDataSource,
        HikariDataSource dispatcherDataSource
    ) {
        this.appDataSource = appDataSource;
        this.dispatcherDataSource = dispatcherDataSource;
        this.appJdbc = new JdbcTemplate(appDataSource);
        this.dispatcherJdbc = new JdbcTemplate(dispatcherDataSource);
        this.appTransactions = PostgresRoleDataSource.transactions(appDataSource);
        this.dispatcherTransactions = PostgresRoleDataSource.transactions(dispatcherDataSource);
        this.appTenantContext = new TenantContextSql(appJdbc);
        this.dispatcherTenantContext = new OutboxDispatcherTenantContextSql(dispatcherJdbc);
        this.dispatcherScheduler = new OutboxDispatcherTenantScheduler(dispatcherJdbc);
        EventPayloadIntegrity integrity = new EventPayloadIntegrity(new ObjectMapper());
        this.outbox = new TransactionalOutboxRepository(appJdbc, integrity);
        this.leases = new TransactionalOutboxLeaseStore(dispatcherJdbc, integrity);
        this.inbox = new TransactionalInboxRepository(appJdbc);
    }

    static MessagingPostgresTestContext open() throws SQLException {
        PostgresIntegrationEnvironment environment = PostgresIntegrationEnvironment.fromProcess();
        PostgresTenantFixtures.seed(environment);
        HikariDataSource appDataSource = PostgresRoleDataSource.open(
            "phase3-messaging-app", environment, environment.appUser(), environment.appPassword()
        );
        try {
            HikariDataSource dispatcherDataSource = PostgresRoleDataSource.open(
                "phase3-messaging-dispatcher",
                environment,
                environment.dispatcherUser(),
                environment.dispatcherPassword()
            );
            return new MessagingPostgresTestContext(appDataSource, dispatcherDataSource);
        }
        catch (RuntimeException exception) {
            appDataSource.close();
            throw exception;
        }
    }

    void append(EventEnvelope... events) {
        runInTenant(() -> {
            for (EventEnvelope event : events) {
                outbox.append(event);
            }
        });
    }

    List<OutboxLease> claim(UUID leaseToken, Instant now) {
        return claim(TENANT_A, leaseToken, now);
    }

    List<OutboxLease> claim(UUID organizationId, UUID leaseToken, Instant now) {
        return callInDispatcherTenant(organizationId, () ->
            leases.claimBatch(organizationId, leaseToken, now, LEASE_DURATION, 10));
    }

    boolean markPublished(UUID eventId, UUID leaseToken, Instant publishedAt) {
        return markPublished(TENANT_A, eventId, leaseToken, publishedAt);
    }

    boolean markPublished(
        UUID organizationId,
        UUID eventId,
        UUID leaseToken,
        Instant publishedAt
    ) {
        return callInDispatcherTenant(organizationId, () ->
            leases.markPublished(organizationId, eventId, leaseToken, publishedAt));
    }

    boolean releaseFailure(
        UUID eventId,
        UUID leaseToken,
        Instant failedAt,
        Instant retryAt,
        boolean poison
    ) {
        return callInDispatcherTenant(TENANT_A, () -> leases.releaseAfterFailure(
            TENANT_A,
            eventId,
            leaseToken,
            poison ? "publisher.poison" : "publisher.transient",
            failedAt,
            retryAt,
            poison
        ));
    }

    TransactionalOutboxRepository outbox() {
        return outbox;
    }

    TransactionalInboxRepository inbox() {
        return inbox;
    }

    JdbcTemplate jdbc() {
        return appJdbc;
    }

    JdbcTemplate dispatcherJdbc() {
        return dispatcherJdbc;
    }

    List<UUID> listReadyTenants(int limit) {
        return callInDispatcher(() -> dispatcherScheduler.listReadyTenants(limit));
    }

    UUID applyDispatcherContext(UUID organizationId) {
        return dispatcherTenantContext.apply(organizationId);
    }

    void insertSideEffect(String idempotencyKey) {
        int updated = appJdbc.update(
            "INSERT INTO idempotency_records "
                + "(organization_id, idempotency_key, actor_id, request_digest, status, "
                + "response_status, response_body, completed_at) "
                + "VALUES (?, ?, ?, ?, 'succeeded', 200, '{}'::jsonb, clock_timestamp())",
            TENANT_A,
            idempotencyKey,
            USER_A,
            RequestDigest.sha256(idempotencyKey.getBytes(StandardCharsets.UTF_8))
        );
        if (updated != 1) {
            throw new IllegalStateException("Expected exactly one local side effect.");
        }
    }

    int sideEffectCount(String idempotencyKey) {
        return callInTenant(() -> Objects.requireNonNull(appJdbc.queryForObject(
            "SELECT count(*) FROM idempotency_records WHERE idempotency_key = ?",
            Integer.class,
            idempotencyKey
        )));
    }

    void runInTenant(Runnable action) {
        runInTenant(TENANT_A, USER_A, action);
    }

    void runInTenant(UUID organizationId, UUID actorId, Runnable action) {
        appTransactions.executeWithoutResult(status -> {
            appTenantContext.apply(organizationId, actorId);
            action.run();
        });
    }

    <T> T callInTenant(Supplier<T> action) {
        return Objects.requireNonNull(appTransactions.execute(status -> {
            appTenantContext.apply(TENANT_A, USER_A);
            return action.get();
        }));
    }

    <T> T callInDispatcher(Supplier<T> action) {
        return Objects.requireNonNull(dispatcherTransactions.execute(status -> action.get()));
    }

    <T> T callInDispatcherTenant(UUID organizationId, Supplier<T> action) {
        return Objects.requireNonNull(dispatcherTransactions.execute(status -> {
            dispatcherTenantContext.apply(organizationId);
            return action.get();
        }));
    }

    @Override
    public void close() {
        dispatcherDataSource.close();
        appDataSource.close();
    }
}
