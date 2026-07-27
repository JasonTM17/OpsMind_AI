package ai.opsmind.platform.incident;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.net.URI;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

import ai.opsmind.platform.common.api.PlatformProblemException;
import ai.opsmind.platform.identity.OpsMindPrincipal;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.mockito.ArgumentMatchers;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.transaction.CannotCreateTransactionException;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.TransactionStatus;

class IncidentQueryServiceTest {

    private static final UUID ORGANIZATION_ID = UUID.fromString("11111111-1111-4111-8111-111111111111");
    private static final UUID PROJECT_ID = UUID.fromString("22222222-2222-4222-8222-222222222222");
    private static final UUID INCIDENT_ID = UUID.fromString("33333333-3333-4333-8333-333333333333");
    private static final UUID ACTOR_ID = UUID.fromString("44444444-4444-4444-8444-444444444444");
    private static final Instant NOW = Instant.parse("2030-01-01T00:00:00Z");

    private final PlatformTransactionManager transactionManager = mock(PlatformTransactionManager.class);
    private final TransactionStatus transactionStatus = mock(TransactionStatus.class);
    private final IncidentAccessRepository access = mock(IncidentAccessRepository.class);
    private final IncidentRepository incidents = mock(IncidentRepository.class);
    private final IncidentTimelineRepository timeline = mock(IncidentTimelineRepository.class);
    private final IncidentTimelinePageToken pageToken = new IncidentTimelinePageToken();
    private final IncidentQueryService service = new IncidentQueryService(
        transactionManager,
        access,
        incidents,
        timeline,
        pageToken
    );

    @BeforeEach
    void configureTransaction() {
        when(transactionManager.getTransaction(any(TransactionDefinition.class)))
            .thenReturn(transactionStatus);
        when(access.requireAccess(any(), eq(ORGANIZATION_ID), eq(PROJECT_ID), eq(IncidentAccessMode.READ)))
            .thenReturn(new IncidentActor(ACTOR_ID, "SRE", "SRE"));
    }

    @Test
    void detailReturnsCurrentVersionEtagInsideAuthorizedTransaction() {
        when(incidents.find(ORGANIZATION_ID, PROJECT_ID, INCIDENT_ID))
            .thenReturn(Optional.of(snapshot(5)));

        IncidentDetailResult result = service.detail(
            principal(), ORGANIZATION_ID, PROJECT_ID, INCIDENT_ID
        );

        assertThat(result.etag()).isEqualTo("\"5\"");
        assertThat(result.incident().id()).isEqualTo(INCIDENT_ID);
        verify(access).requireAccess(any(), eq(ORGANIZATION_ID), eq(PROJECT_ID), eq(IncidentAccessMode.READ));
        verify(transactionManager).commit(transactionStatus);
    }

    @Test
    void timelineUsesVersionCursorAndReturnsBoundedPage() {
        when(incidents.find(ORGANIZATION_ID, PROJECT_ID, INCIDENT_ID))
            .thenReturn(Optional.of(snapshot(3)));
        when(timeline.list(ORGANIZATION_ID, PROJECT_ID, INCIDENT_ID, null, 3))
            .thenReturn(List.of(event(0), event(1), event(2)));

        IncidentTimelinePage result = service.timeline(
            principal(), ORGANIZATION_ID, PROJECT_ID, INCIDENT_ID, 2, null
        );

        assertThat(result.items()).extracting(IncidentTimelineEvent::incidentVersion)
            .containsExactly(0L, 1L);
        assertThat(result.hasMore()).isTrue();
        assertThat(result.nextPageToken()).isNotBlank();
        assertThat(pageToken.decode(result.nextPageToken(), INCIDENT_ID)).isEqualTo(1L);
        verify(timeline).list(ORGANIZATION_ID, PROJECT_ID, INCIDENT_ID, null, 3);
        verify(transactionManager).commit(transactionStatus);
    }

    @Test
    void activityTimelineUsesAnalyzeAuthorizationAndTupleCursor() {
        when(access.requireAccess(
            any(), eq(ORGANIZATION_ID), eq(PROJECT_ID), eq(IncidentAccessMode.ANALYZE)
        )).thenReturn(new IncidentActor(ACTOR_ID, "SRE", "SRE"));
        when(incidents.find(ORGANIZATION_ID, PROJECT_ID, INCIDENT_ID))
            .thenReturn(Optional.of(snapshot(3)));
        IncidentActivityTimelineEntry first = activityEntry(
            IncidentActivityTimelineEntry.INCIDENT, IncidentTimelineEvent.CREATED, 0L, null, null
        );
        IncidentActivityTimelineEntry second = activityEntry(
            IncidentActivityTimelineEntry.INVESTIGATION,
            IncidentActivityTimelineEntry.RUN_STARTED,
            null,
            UUID.fromString("55555555-5555-4555-8555-555555555555"),
            1L
        );
        when(timeline.listActivity(
            ORGANIZATION_ID, PROJECT_ID, INCIDENT_ID, null, 3
        )).thenReturn(List.of(first, second, activityEntry(
            IncidentActivityTimelineEntry.INCIDENT,
            IncidentTimelineEvent.STATUS_TRANSITIONED,
            1L,
            null,
            null
        )));

        IncidentActivityTimelinePage result = service.activityTimeline(
            analyzePrincipal(), ORGANIZATION_ID, PROJECT_ID, INCIDENT_ID, 2, null
        );

        assertThat(result.items()).containsExactly(first, second);
        assertThat(result.hasMore()).isTrue();
        assertThat(pageToken.decodeActivity(result.nextPageToken(), INCIDENT_ID))
            .isEqualTo(new IncidentTimelinePageToken.ActivityCursor(
                second.occurredAt(), 1, second.eventId()
            ));
        verify(access).requireAccess(
            any(), eq(ORGANIZATION_ID), eq(PROJECT_ID), eq(IncidentAccessMode.ANALYZE)
        );
        verify(timeline).listActivity(ORGANIZATION_ID, PROJECT_ID, INCIDENT_ID, null, 3);
        verify(transactionManager).commit(transactionStatus);
    }

    @Test
    void activityTimelineRequiresAnalyzeScopeBeforeOpeningTransaction() {
        assertThatThrownBy(() -> service.activityTimeline(
            principal(), ORGANIZATION_ID, PROJECT_ID, INCIDENT_ID, 25, null
        )).isInstanceOfSatisfying(PlatformProblemException.class, exception -> {
            assertThat(exception.status()).isEqualTo(HttpStatus.FORBIDDEN);
            assertThat(exception.code()).isEqualTo("authorization.scope-required");
        });

        verify(transactionManager, never()).getTransaction(any());
        verify(access, never()).requireAccess(any(), any(), any(), any());
    }

    @Test
    void activityTimelinePreservesHiddenDenialForAnalyzeRoles() {
        when(access.requireAccess(
            any(), eq(ORGANIZATION_ID), eq(PROJECT_ID), eq(IncidentAccessMode.ANALYZE)
        )).thenThrow(IncidentRolePolicy.hiddenDenial());

        assertThatThrownBy(() -> service.activityTimeline(
            analyzePrincipal(), ORGANIZATION_ID, PROJECT_ID, INCIDENT_ID, 25, null
        )).isInstanceOfSatisfying(PlatformProblemException.class, exception -> {
            assertThat(exception.status()).isEqualTo(HttpStatus.NOT_FOUND);
            assertThat(exception.code()).isEqualTo("resource.not-found");
        });

        verify(incidents, never()).find(any(), any(), any());
        verify(timeline, never()).listActivity(any(), any(), any(), any(), any(Integer.class));
        verify(transactionManager).rollback(transactionStatus);
    }

    @Test
    void activityRepositoryBoundsEachSourceWithIndexableCursorParameters() {
        CapturedActivityQuery initial = captureActivityQuery(null);

        assertThat(initial.sql()).contains(
            "ORDER BY occurred_at ASC, event_id ASC LIMIT ?",
            "UNION ALL",
            "ORDER BY occurred_at ASC, source_rank ASC, event_id ASC LIMIT ?"
        ).doesNotContain("payload", "(occurred_at, 0, event_id)", "(occurred_at, 1, event_id)");
        assertThat(occurrences(initial.sql(), "ORDER BY occurred_at ASC, event_id ASC LIMIT ?")).isEqualTo(2);
        assertThat(initial.parameters()).containsExactly(
            ORGANIZATION_ID, PROJECT_ID, INCIDENT_ID, 3,
            ORGANIZATION_ID, PROJECT_ID, INCIDENT_ID, 3,
            3
        );

        Instant cursorTime = NOW.plusNanos(1_000L);
        UUID cursorEventId = UUID.fromString("55555555-5555-4555-8555-555555555555");
        CapturedActivityQuery afterIncident = captureActivityQuery(
            new IncidentTimelinePageToken.ActivityCursor(cursorTime, 0, cursorEventId)
        );

        assertThat(afterIncident.sql()).contains(
            "AND (occurred_at, event_id) > (?, ?)",
            "AND occurred_at >= ?"
        );
        assertThat(afterIncident.parameters()).containsExactly(
            ORGANIZATION_ID, PROJECT_ID, INCIDENT_ID, Timestamp.from(cursorTime), cursorEventId, 3,
            ORGANIZATION_ID, PROJECT_ID, INCIDENT_ID, Timestamp.from(cursorTime), 3,
            3
        );

        CapturedActivityQuery afterInvestigation = captureActivityQuery(
            new IncidentTimelinePageToken.ActivityCursor(cursorTime, 1, cursorEventId)
        );

        assertThat(afterInvestigation.sql()).contains(
            "AND occurred_at > ?",
            "AND (occurred_at, event_id) > (?, ?)"
        );
        assertThat(afterInvestigation.parameters()).containsExactly(
            ORGANIZATION_ID, PROJECT_ID, INCIDENT_ID, Timestamp.from(cursorTime), 3,
            ORGANIZATION_ID, PROJECT_ID, INCIDENT_ID, Timestamp.from(cursorTime), cursorEventId, 3,
            3
        );
    }

    @Test
    void hiddenAccessDenialRollsBackBeforeResourceLookup() {
        when(access.requireAccess(any(), eq(ORGANIZATION_ID), eq(PROJECT_ID), eq(IncidentAccessMode.READ)))
            .thenThrow(IncidentRolePolicy.hiddenDenial());

        assertThatThrownBy(() -> service.detail(
            principal(), ORGANIZATION_ID, PROJECT_ID, INCIDENT_ID
        )).isInstanceOfSatisfying(PlatformProblemException.class, exception -> {
            assertThat(exception.status()).isEqualTo(HttpStatus.NOT_FOUND);
            assertThat(exception.code()).isEqualTo("resource.not-found");
        });

        verify(incidents, never()).find(any(), any(), any());
        verify(transactionManager).rollback(transactionStatus);
    }

    @Test
    void transactionInfrastructureFailureMapsToServiceUnavailable() {
        when(transactionManager.getTransaction(any(TransactionDefinition.class)))
            .thenThrow(new CannotCreateTransactionException("database offline"));

        assertThatThrownBy(() -> service.detail(
            principal(), ORGANIZATION_ID, PROJECT_ID, INCIDENT_ID
        )).isInstanceOfSatisfying(PlatformProblemException.class, exception -> {
            assertThat(exception.status()).isEqualTo(HttpStatus.SERVICE_UNAVAILABLE);
            assertThat(exception.code()).isEqualTo("incident.transaction-unavailable");
        });

        verify(access, never()).requireAccess(any(), any(), any(), any());
    }

    private OpsMindPrincipal principal() {
        return principal(Set.of("incident:read"));
    }

    private OpsMindPrincipal analyzePrincipal() {
        return principal(Set.of("incident:analyze"));
    }

    private OpsMindPrincipal principal(Set<String> scopes) {
        return new OpsMindPrincipal(
            URI.create("https://idp.example.test/opsmind"),
            "operator-001",
            null,
            null,
            scopes
        );
    }

    private IncidentSnapshot snapshot(long version) {
        return new IncidentSnapshot(
            INCIDENT_ID, ORGANIZATION_ID, PROJECT_ID, "API unavailable", "5xx spike",
            IncidentSeverity.SEV1, IncidentStatus.INVESTIGATING, null, null,
            ACTOR_ID, ACTOR_ID, NOW, NOW, version
        );
    }

    private IncidentTimelineEvent event(long version) {
        return new IncidentTimelineEvent(
            UUID.randomUUID(), ORGANIZATION_ID, PROJECT_ID, INCIDENT_ID, version,
            version == 0 ? IncidentTimelineEvent.CREATED : IncidentTimelineEvent.STATUS_TRANSITIONED,
            ACTOR_ID, UUID.randomUUID(), NOW.plusSeconds(version), "operator update",
            null, IncidentStatus.INVESTIGATING, null, null
        );
    }

    private IncidentActivityTimelineEntry activityEntry(
        String source,
        String eventType,
        Long incidentVersion,
        UUID runId,
        Long sequence
    ) {
        return new IncidentActivityTimelineEntry(
            UUID.randomUUID(),
            source,
            eventType,
            NOW.plusNanos((incidentVersion == null ? sequence : incidentVersion) * 1_000L),
            ACTOR_ID,
            incidentVersion,
            runId,
            sequence
        );
    }

    private CapturedActivityQuery captureActivityQuery(IncidentTimelinePageToken.ActivityCursor after) {
        JdbcTemplate jdbcTemplate = mock(JdbcTemplate.class);
        JdbcIncidentTimelineRepository repository = new JdbcIncidentTimelineRepository(jdbcTemplate);

        repository.listActivity(ORGANIZATION_ID, PROJECT_ID, INCIDENT_ID, after, 3);

        ArgumentCaptor<String> sql = ArgumentCaptor.forClass(String.class);
        ArgumentCaptor<Object[]> parameters = ArgumentCaptor.forClass(Object[].class);
        verify(jdbcTemplate).query(
            sql.capture(),
            ArgumentMatchers.<RowMapper<IncidentActivityTimelineEntry>>any(),
            parameters.capture()
        );
        return new CapturedActivityQuery(sql.getValue(), parameters.getValue());
    }

    private int occurrences(String value, String fragment) {
        int count = 0;
        int index = 0;
        while ((index = value.indexOf(fragment, index)) >= 0) {
            count++;
            index += fragment.length();
        }
        return count;
    }

    private record CapturedActivityQuery(String sql, Object[] parameters) {
    }
}
