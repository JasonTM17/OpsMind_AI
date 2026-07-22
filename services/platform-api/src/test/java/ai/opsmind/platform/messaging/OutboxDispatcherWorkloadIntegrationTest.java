package ai.opsmind.platform.messaging;

import static ai.opsmind.platform.testing.PostgresTenantFixtures.DISPATCHER_A;
import static ai.opsmind.platform.testing.PostgresTenantFixtures.TENANT_A;
import static ai.opsmind.platform.testing.PostgresTenantFixtures.TENANT_B;
import static ai.opsmind.platform.testing.PostgresTenantFixtures.USER_B;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Instant;
import java.util.UUID;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.dao.DataAccessException;

@EnabledIfEnvironmentVariable(named = "OPSMIND_PHASE3_DB_INTEGRATION", matches = "true")
class OutboxDispatcherWorkloadIntegrationTest {

    private static final Instant BASE_TIME = Instant.parse("2030-02-01T00:00:00Z");
    private static final UUID EVENT_A = UUID.fromString("d15a1001-d15a-415a-815a-d15a00000001");
    private static final UUID EVENT_B = UUID.fromString("d15b1001-d15b-415b-815b-d15b00000001");
    private static final UUID AGGREGATE_A = UUID.fromString("d15a2001-d15a-415a-815a-d15a00000001");
    private static final UUID AGGREGATE_B = UUID.fromString("d15b2001-d15b-415b-815b-d15b00000001");

    @Test
    void separatesAppendFromTenantBoundLeaseAuthority() throws Exception {
        try (MessagingPostgresTestContext context = MessagingPostgresTestContext.open()) {
            EventEnvelope tenantAEvent = event(EVENT_A, TENANT_A, AGGREGATE_A, BASE_TIME);
            EventEnvelope tenantBEvent = event(EVENT_B, TENANT_B, AGGREGATE_B, BASE_TIME.plusSeconds(1));
            context.append(tenantAEvent);
            context.runInTenant(TENANT_B, USER_B, () -> context.outbox().append(tenantBEvent));

            assertThat(context.listReadyTenants(10)).containsExactly(TENANT_A, TENANT_B);
            assertThat(context.callInDispatcher(() -> context.dispatcherJdbc().queryForObject(
                "SELECT count(*) FROM outbox_events",
                Integer.class
            ))).isZero();

            assertThatThrownBy(() -> context.callInTenant(() -> context.jdbc().update(
                "UPDATE outbox_events SET attempts = attempts + 1 WHERE event_id = ?",
                EVENT_A
            ))).isInstanceOf(DataAccessException.class);
            assertThatThrownBy(() -> context.callInDispatcher(() -> context.dispatcherJdbc()
                .queryForObject("SELECT count(*) FROM service_accounts", Integer.class)))
                .isInstanceOf(DataAccessException.class);

            UUID workloadId = context.callInDispatcherTenant(TENANT_A, () ->
                context.dispatcherJdbc().queryForObject(
                    "SELECT public.opsmind_current_workload_id()",
                    UUID.class
                ));
            assertThat(workloadId).isEqualTo(DISPATCHER_A);

            assertThatThrownBy(() -> context.callInDispatcher(() -> {
                context.applyDispatcherContext(TENANT_A);
                return context.applyDispatcherContext(TENANT_B);
            })).isInstanceOf(DataAccessException.class)
                .rootCause().hasMessageContaining("already bound to another tenant");

            UUID unauthorizedTenant = UUID.fromString("cccccccc-cccc-4ccc-8ccc-cccccccccccc");
            assertThatThrownBy(() -> context.callInDispatcher(() ->
                context.applyDispatcherContext(unauthorizedTenant)))
                .isInstanceOf(DataAccessException.class)
                .rootCause().hasMessageContaining("active tenant-scoped dispatcher account is required");

            UUID leaseA = UUID.fromString("d15a3001-d15a-415a-815a-d15a00000001");
            OutboxLease claimedA = context.claim(TENANT_A, leaseA, BASE_TIME.plusSeconds(10)).getFirst();
            assertThat(claimedA.event().eventId()).isEqualTo(EVENT_A);
            assertThat(context.listReadyTenants(10)).containsExactly(TENANT_B);
            assertThat(context.markPublished(
                TENANT_A, EVENT_A, leaseA, BASE_TIME.plusSeconds(10)
            )).isTrue();

            UUID leaseB = UUID.fromString("d15b3001-d15b-415b-815b-d15b00000001");
            OutboxLease claimedB = context.claim(TENANT_B, leaseB, BASE_TIME.plusSeconds(11)).getFirst();
            assertThat(claimedB.event().eventId()).isEqualTo(EVENT_B);
            assertThat(context.markPublished(
                TENANT_B, EVENT_B, leaseB, BASE_TIME.plusSeconds(11)
            )).isTrue();
            assertThat(context.listReadyTenants(10)).isEmpty();

            assertThat(context.callInDispatcher(() -> context.dispatcherJdbc().queryForMap(
                "SELECT public.opsmind_current_tenant_id() AS tenant, "
                    + "public.opsmind_current_workload_id() AS workload"
            ))).containsEntry("tenant", null).containsEntry("workload", null);
        }
    }

    private static EventEnvelope event(
        UUID eventId,
        UUID organizationId,
        UUID aggregateId,
        Instant occurredAt
    ) throws Exception {
        String payload = "{\"source\":\"dispatcher-contract\"}";
        return new EventEnvelope(
            eventId,
            organizationId,
            "dispatcher-contract",
            aggregateId,
            1,
            "dispatcher.event",
            "1",
            null,
            UUID.fromString("d15c0001-d15c-415c-815c-d15c00000001"),
            occurredAt,
            payload,
            MessageDigest.getInstance("SHA-256").digest(payload.getBytes(StandardCharsets.UTF_8))
        );
    }
}
