package ai.opsmind.platform.messaging;

import java.sql.Timestamp;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

import org.springframework.jdbc.core.JdbcTemplate;

final class TransactionalOutboxClaimer {

    private final JdbcTemplate jdbcTemplate;

    TransactionalOutboxClaimer(JdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
    }

    List<OutboxLease> claim(
        UUID organizationId,
        UUID leaseToken,
        Instant now,
        Duration leaseDuration,
        int limit,
        String eventType
    ) {
        String eventPredicate = eventType == null ? "" : "AND candidate.event_type = ? ";
        String sql = """
            WITH candidates AS (
                SELECT candidate.event_id
                  FROM outbox_events candidate
                 WHERE candidate.organization_id = ?
                   AND candidate.published_at IS NULL
                   AND candidate.poisoned_at IS NULL
                   AND candidate.next_attempt_at <= ?
                   AND (candidate.lease_expires_at IS NULL OR candidate.lease_expires_at <= ?)
                   %s
                   -- Canonical investigation workflow starts are reserved for the
                   -- resolver-owned V012 claim path. Keep this query fence in
                   -- addition to the dispatcher RLS policy so a generic batch
                   -- cannot lease one when the tenant also has ordinary work.
                   AND NOT (
                       candidate.event_type = 'investigation.workflow-start.requested'
                       AND candidate.schema_version = '1'
                       AND candidate.aggregate_type = 'investigation-workflow'
                       AND candidate.aggregate_sequence = 1
                   )
                   AND NOT public.opsmind_has_unpublished_outbox_predecessor(
                       candidate.organization_id,
                       candidate.aggregate_type,
                       candidate.aggregate_id,
                       candidate.aggregate_sequence
                   )
                 ORDER BY candidate.occurred_at, candidate.event_id
                 FOR UPDATE SKIP LOCKED
                 LIMIT ?
            )
            UPDATE outbox_events claimed
               SET lease_token = ?, lease_expires_at = ?, attempts = attempts + 1, last_error = NULL
              FROM candidates
             WHERE claimed.event_id = candidates.event_id
            RETURNING claimed.*
            """.formatted(eventPredicate);
        List<Object> arguments = new ArrayList<>();
        arguments.add(organizationId);
        arguments.add(Timestamp.from(now));
        arguments.add(Timestamp.from(now));
        if (eventType != null) arguments.add(eventType);
        arguments.add(limit);
        arguments.add(leaseToken);
        arguments.add(Timestamp.from(now.plus(leaseDuration)));
        return jdbcTemplate.query(
            sql,
            OutboxLeaseRowMapper.INSTANCE,
            arguments.toArray()
        );
    }
}
