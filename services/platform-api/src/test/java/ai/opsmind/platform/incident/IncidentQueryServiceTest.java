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
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

import ai.opsmind.platform.common.api.PlatformProblemException;
import ai.opsmind.platform.identity.OpsMindPrincipal;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
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
        return new OpsMindPrincipal(
            URI.create("https://idp.example.test/opsmind"),
            "operator-001",
            null,
            null,
            Set.of("incident:read")
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
}
