package ai.opsmind.platform.messaging;

import static ai.opsmind.platform.testing.PostgresTenantFixtures.TENANT_A;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Instant;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;

import ai.opsmind.platform.common.api.PlatformProblemException;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;

@EnabledIfEnvironmentVariable(named = "OPSMIND_PHASE3_DB_INTEGRATION", matches = "true")
class TransactionalOutboxIntegrationTest {

    private static final UUID AGGREGATE_ID = UUID.fromString("33333333-3333-4333-8333-333333333333");
    private static final UUID EVENT_ONE = UUID.fromString("30000001-3333-4333-8333-333333333333");
    private static final UUID EVENT_TWO = UUID.fromString("30000002-3333-4333-8333-333333333333");
    private static final Instant BASE_TIME = Instant.parse("2030-01-01T00:00:00Z");
    private static final String SIDE_EFFECT_KEY = "phase3-effect-outbox";

    @Test
    void convergesAcrossCommitPublishAcknowledgementRetryOrderingAndPoisonWindows() throws Exception {
        try (MessagingPostgresTestContext context = MessagingPostgresTestContext.open()) {
            EventEnvelope first = event(EVENT_ONE, 1, "{\"z\":1, \"a\":2}");
            EventEnvelope second = event(EVENT_TWO, 2, "{\"step\":2}");

            proveAppendAndDomainChangeRollBackTogether(context, first);
            context.append(first, second);
            assertThatThrownBy(() -> context.append(event(
                UUID.fromString("30000004-3333-4333-8333-333333333333"),
                4,
                "{\"step\":4}"
            ))).isInstanceOf(PlatformProblemException.class).hasMessageContaining("not contiguous");

            OutboxLease abandoned = single(context.claim(lease(1), BASE_TIME));
            assertThat(abandoned.attempt()).isEqualTo(1);
            assertThat(abandoned.event().eventId()).isEqualTo(first.eventId());
            assertThat(context.claim(lease(2), BASE_TIME.plusSeconds(4))).isEmpty();

            OutboxLease publishedBeforeCrash = single(context.claim(lease(3), BASE_TIME.plusSeconds(5)));
            assertThat(publishedBeforeCrash.attempt()).isEqualTo(2);
            assertThat(publishedBeforeCrash.event().payloadJson()).isEqualTo(first.payloadJson());
            assertThat(context.markPublished(first.eventId(), lease(1), BASE_TIME.plusSeconds(5)))
                .isFalse();

            Set<UUID> logicalEffects = new HashSet<>();
            AtomicInteger physicalPublishes = new AtomicInteger();
            publish(publishedBeforeCrash, logicalEffects, physicalPublishes);

            OutboxLease recovered = single(context.claim(lease(4), BASE_TIME.plusSeconds(10)));
            assertThat(recovered.attempt()).isEqualTo(3);
            publish(recovered, logicalEffects, physicalPublishes);
            assertThat(context.markPublished(first.eventId(), lease(4), BASE_TIME.plusSeconds(10)))
                .isTrue();
            assertThat(physicalPublishes).hasValue(2);
            assertThat(logicalEffects).containsExactly(first.eventId());

            proveTransientRetryThenPoison(context, second);
        }
    }

    private static void proveAppendAndDomainChangeRollBackTogether(
        MessagingPostgresTestContext context,
        EventEnvelope event
    ) {
        assertThat(context.sideEffectCount(SIDE_EFFECT_KEY)).isZero();
        assertThatThrownBy(() -> context.runInTenant(() -> {
            context.insertSideEffect(SIDE_EFFECT_KEY);
            context.outbox().append(event);
            throw new SimulatedCrash();
        })).isInstanceOf(SimulatedCrash.class);
        assertThat(context.sideEffectCount(SIDE_EFFECT_KEY)).isZero();
        assertThat(context.callInTenant(() -> context.jdbc().queryForObject(
            "SELECT count(*) FROM outbox_events WHERE event_id = ?",
            Integer.class,
            event.eventId()
        ))).isZero();
    }

    private static void proveTransientRetryThenPoison(
        MessagingPostgresTestContext context,
        EventEnvelope second
    ) {
        OutboxLease firstAttempt = single(context.claim(lease(5), BASE_TIME.plusSeconds(11)));
        assertThat(firstAttempt.event().eventId()).isEqualTo(second.eventId());
        assertThat(context.releaseFailure(
            second.eventId(), lease(5), BASE_TIME.plusSeconds(11), BASE_TIME.plusSeconds(20), false
        )).isTrue();
        assertThat(context.claim(lease(6), BASE_TIME.plusSeconds(19))).isEmpty();

        OutboxLease finalAttempt = single(context.claim(lease(7), BASE_TIME.plusSeconds(20)));
        assertThat(finalAttempt.attempt()).isEqualTo(2);
        assertThat(context.releaseFailure(
            second.eventId(), lease(7), BASE_TIME.plusSeconds(20), BASE_TIME.plusSeconds(20), true
        )).isTrue();
        assertThat(context.claim(lease(8), BASE_TIME.plusSeconds(100))).isEmpty();
        assertThat(context.callInTenant(() -> context.jdbc().queryForObject(
            "SELECT poisoned_at IS NOT NULL FROM outbox_events WHERE event_id = ?",
            Boolean.class,
            second.eventId()
        ))).isTrue();
    }

    private static OutboxLease single(List<OutboxLease> leases) {
        assertThat(leases).hasSize(1);
        return leases.getFirst();
    }

    private static void publish(
        OutboxLease lease,
        Set<UUID> logicalEffects,
        AtomicInteger physicalPublishes
    ) {
        physicalPublishes.incrementAndGet();
        logicalEffects.add(lease.event().eventId());
    }

    private static UUID lease(int number) {
        return UUID.fromString("5000000" + number + "-5555-4555-8555-555555555555");
    }

    private static EventEnvelope event(UUID eventId, long sequence, String payload) throws Exception {
        return new EventEnvelope(
            eventId,
            TENANT_A,
            "phase3-contract",
            AGGREGATE_ID,
            sequence,
            "phase3.event",
            "1",
            null,
            UUID.fromString("60000001-6666-4666-8666-666666666666"),
            BASE_TIME.plusSeconds(sequence),
            payload,
            MessageDigest.getInstance("SHA-256").digest(payload.getBytes(StandardCharsets.UTF_8))
        );
    }

    private static final class SimulatedCrash extends RuntimeException {
        private static final long serialVersionUID = 1L;
    }
}
