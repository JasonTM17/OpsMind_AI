package ai.opsmind.platform.messaging;

import static ai.opsmind.platform.testing.PostgresTenantFixtures.TENANT_A;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.UUID;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;

@EnabledIfEnvironmentVariable(named = "OPSMIND_PHASE3_DB_INTEGRATION", matches = "true")
class TransactionalInboxIntegrationTest {

    private static final UUID EVENT = UUID.fromString("40000001-4444-4444-8444-444444444444");
    private static final UUID ORPHAN_EVENT = UUID.fromString("40000002-4444-4444-8444-444444444444");
    private static final UUID POISON_EVENT = UUID.fromString("40000003-4444-4444-8444-444444444444");
    private static final String CONSUMER = "phase3-fault-contract";
    private static final String EFFECT_KEY = "phase3-effect-inbox";
    private static final String ORPHAN_EFFECT_KEY = "phase3-effect-orphan";

    @Test
    void appliesOneLogicalSideEffectAcrossRollbackAckLossOrphanAndPoisonPaths() throws Exception {
        try (MessagingPostgresTestContext context = MessagingPostgresTestContext.open()) {
            proveHandlerCrashRollsBackClaimAndSideEffect(context);
            proveAcknowledgementLossIsDeduplicated(context);
            proveReceivedOrphanCanBeReclaimed(context);
            provePoisonedEventCannotBeReclaimed(context);
        }
    }

    private static void proveHandlerCrashRollsBackClaimAndSideEffect(
        MessagingPostgresTestContext context
    ) {
        assertThatThrownBy(() -> context.runInTenant(() -> {
            assertThat(context.inbox().claim(TENANT_A, EVENT, CONSUMER)).isTrue();
            context.insertSideEffect(EFFECT_KEY);
            throw new SimulatedCrash();
        })).isInstanceOf(SimulatedCrash.class);
        assertThat(context.sideEffectCount(EFFECT_KEY)).isZero();
        assertThat(context.callInTenant(() -> context.jdbc().queryForObject(
            "SELECT count(*) FROM inbox_events WHERE event_id = ?",
            Integer.class,
            EVENT
        ))).isZero();
    }

    private static void proveAcknowledgementLossIsDeduplicated(
        MessagingPostgresTestContext context
    ) {
        context.runInTenant(() -> {
            assertThat(context.inbox().claim(TENANT_A, EVENT, CONSUMER)).isTrue();
            context.insertSideEffect(EFFECT_KEY);
            assertThat(context.inbox().markProcessed(TENANT_A, EVENT, CONSUMER)).isTrue();
        });
        context.runInTenant(() ->
            assertThat(context.inbox().claim(TENANT_A, EVENT, CONSUMER)).isFalse()
        );
        assertThat(context.sideEffectCount(EFFECT_KEY)).isEqualTo(1);
    }

    private static void proveReceivedOrphanCanBeReclaimed(MessagingPostgresTestContext context) {
        context.runInTenant(() ->
            assertThat(context.inbox().claim(TENANT_A, ORPHAN_EVENT, CONSUMER)).isTrue()
        );
        context.runInTenant(() -> {
            assertThat(context.inbox().claim(TENANT_A, ORPHAN_EVENT, CONSUMER)).isTrue();
            context.insertSideEffect(ORPHAN_EFFECT_KEY);
            assertThat(context.inbox().markProcessed(TENANT_A, ORPHAN_EVENT, CONSUMER)).isTrue();
        });
        assertThat(context.sideEffectCount(ORPHAN_EFFECT_KEY)).isEqualTo(1);
        assertThat(context.callInTenant(() -> context.jdbc().queryForObject(
            "SELECT attempts FROM inbox_events WHERE event_id = ? AND consumer = ?",
            Integer.class,
            ORPHAN_EVENT,
            CONSUMER
        ))).isEqualTo(2);
    }

    private static void provePoisonedEventCannotBeReclaimed(MessagingPostgresTestContext context) {
        context.runInTenant(() -> {
            assertThat(context.inbox().claim(TENANT_A, POISON_EVENT, CONSUMER)).isTrue();
            assertThat(context.inbox().markPoisoned(
                TENANT_A,
                POISON_EVENT,
                CONSUMER,
                "handler.schema-invalid"
            )).isTrue();
        });
        context.runInTenant(() ->
            assertThat(context.inbox().claim(TENANT_A, POISON_EVENT, CONSUMER)).isFalse()
        );
    }

    private static final class SimulatedCrash extends RuntimeException {
        private static final long serialVersionUID = 1L;
    }
}
