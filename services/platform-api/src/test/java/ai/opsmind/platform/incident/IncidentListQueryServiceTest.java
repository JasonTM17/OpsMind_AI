package ai.opsmind.platform.incident;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.net.URI;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.List;
import java.util.Set;
import java.util.UUID;

import ai.opsmind.platform.common.api.PlatformProblemException;
import ai.opsmind.platform.identity.OpsMindPrincipal;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.mockito.ArgumentMatchers;
import org.mockito.InOrder;
import org.springframework.dao.DataAccessResourceFailureException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.TransactionStatus;

class IncidentListQueryServiceTest {

    private static final UUID ORGANIZATION_ID =
        UUID.fromString("11111111-1111-4111-8111-111111111111");
    private static final UUID PROJECT_ID =
        UUID.fromString("22222222-2222-4222-8222-222222222222");
    private static final UUID ACTOR_ID =
        UUID.fromString("44444444-4444-4444-8444-444444444444");
    private static final Instant NOW = Instant.parse("2030-01-01T00:00:00.123456Z");

    private final PlatformTransactionManager transactionManager = mock(PlatformTransactionManager.class);
    private final TransactionStatus transactionStatus = mock(TransactionStatus.class);
    private final IncidentAccessRepository access = mock(IncidentAccessRepository.class);
    private final IncidentListRepository repository = mock(IncidentListRepository.class);
    private final IncidentListPageToken token = new IncidentListPageToken();
    private final IncidentListQueryService service = new IncidentListQueryService(
        transactionManager, access, repository, token
    );

    @BeforeEach
    void configureTransaction() {
        when(transactionManager.getTransaction(any(TransactionDefinition.class)))
            .thenReturn(transactionStatus);
        when(access.requireAccess(
            any(), eq(ORGANIZATION_ID), eq(PROJECT_ID), eq(IncidentAccessMode.READ)
        )).thenReturn(new IncidentActor(ACTOR_ID, "SRE", "SRE"));
    }

    @Test
    void returnsBoundedPageAndBindsNextCursorToFilterAndScope() {
        IncidentSummary first = summary("33333333-3333-4333-8333-333333333333", NOW, 3);
        IncidentSummary second = summary("33333333-3333-4333-8333-333333333332", NOW, 2);
        IncidentSummary lookahead = summary(
            "33333333-3333-4333-8333-333333333331", NOW.minusSeconds(1), 1
        );
        when(repository.list(
            ORGANIZATION_ID, PROJECT_ID, IncidentStatus.INVESTIGATING, null, 3
        )).thenReturn(List.of(first, second, lookahead));

        IncidentListPage page = service.list(
            principal(), ORGANIZATION_ID, PROJECT_ID, IncidentStatus.INVESTIGATING, 2, null
        );

        assertThat(page.items()).containsExactly(first, second);
        assertThat(page.hasMore()).isTrue();
        assertThat(page.pageSize()).isEqualTo(2);
        IncidentListPageToken.Claims claims = token.parse(page.nextPageToken());
        assertThat(token.bind(
            claims, ORGANIZATION_ID, PROJECT_ID, IncidentStatus.INVESTIGATING
        )).isEqualTo(new IncidentListPageToken.Cursor(second.updatedAt(), second.id()));
        InOrder order = inOrder(access, repository);
        order.verify(access).requireAccess(
            any(), eq(ORGANIZATION_ID), eq(PROJECT_ID), eq(IncidentAccessMode.READ)
        );
        order.verify(repository).list(
            ORGANIZATION_ID, PROJECT_ID, IncidentStatus.INVESTIGATING, null, 3
        );
        verify(transactionManager).commit(transactionStatus);
    }

    @Test
    void returnsFinalPagesWithoutCursorForEmptyOneAndExactSizeResults() {
        IncidentSummary first = summary("33333333-3333-4333-8333-333333333333", NOW, 3);
        IncidentSummary second = summary("33333333-3333-4333-8333-333333333332", NOW, 2);
        for (List<IncidentSummary> rows : List.of(
            List.<IncidentSummary>of(), List.of(first), List.of(first, second)
        )) {
            when(repository.list(ORGANIZATION_ID, PROJECT_ID, null, null, 3))
                .thenReturn(rows);

            IncidentListPage page = service.list(
                principal(), ORGANIZATION_ID, PROJECT_ID, null, 2, null
            );

            assertThat(page.items()).containsExactlyElementsOf(rows);
            assertThat(page.hasMore()).isFalse();
            assertThat(page.nextPageToken()).isNull();
        }
    }

    @Test
    void authorizesBeforeRejectingForeignCursorBinding() {
        String foreign = token.encode(
            UUID.randomUUID(), PROJECT_ID, null, NOW,
            UUID.fromString("33333333-3333-4333-8333-333333333333")
        );
        when(access.requireAccess(
            any(), eq(ORGANIZATION_ID), eq(PROJECT_ID), eq(IncidentAccessMode.READ)
        )).thenThrow(IncidentRolePolicy.hiddenDenial());

        assertThatThrownBy(() -> service.list(
            principal(), ORGANIZATION_ID, PROJECT_ID, null, 25, foreign
        )).isInstanceOfSatisfying(PlatformProblemException.class, exception ->
            assertThat(exception.code()).isEqualTo("resource.not-found")
        );

        verify(repository, never()).list(any(), any(), any(), any(), any(Integer.class));
        verify(transactionManager).rollback(transactionStatus);
    }

    @Test
    void rejectsFilterMismatchAfterAuthorizedAccessBeforeSelect() {
        String openCursor = token.encode(
            ORGANIZATION_ID,
            PROJECT_ID,
            IncidentStatus.OPEN,
            NOW,
            UUID.fromString("33333333-3333-4333-8333-333333333333")
        );

        assertThatThrownBy(() -> service.list(
            principal(), ORGANIZATION_ID, PROJECT_ID, IncidentStatus.CLOSED, 25, openCursor
        )).isInstanceOfSatisfying(PlatformProblemException.class, exception ->
            assertThat(exception.code()).isEqualTo("pagination.invalid-token")
        );

        verify(access).requireAccess(
            any(), eq(ORGANIZATION_ID), eq(PROJECT_ID), eq(IncidentAccessMode.READ)
        );
        verify(repository, never()).list(any(), any(), any(), any(), any(Integer.class));
        verify(transactionManager).rollback(transactionStatus);
    }

    @Test
    void requiresScopeAndPageBoundsBeforeTransaction() {
        assertThatThrownBy(() -> service.list(
            principal(Set.of()), ORGANIZATION_ID, PROJECT_ID, null, 25, null
        )).isInstanceOf(PlatformProblemException.class);
        assertThatThrownBy(() -> service.list(
            principal(), ORGANIZATION_ID, PROJECT_ID, null, 101, null
        )).isInstanceOf(PlatformProblemException.class);

        verify(transactionManager, never()).getTransaction(any());
        verify(access, never()).requireAccess(any(), any(), any(), any());
    }

    @Test
    void repositoryUsesStrictDescendingTupleForBothQueryForms() {
        assertQuery(
            null,
            null,
            "WHERE organization_id = ? AND project_id = ? ORDER BY updated_at DESC, id DESC LIMIT ?",
            ORGANIZATION_ID, PROJECT_ID, 26
        );
        IncidentListPageToken.Cursor cursor = new IncidentListPageToken.Cursor(
            NOW, UUID.fromString("33333333-3333-4333-8333-333333333333")
        );
        assertQuery(
            IncidentStatus.OPEN,
            cursor,
            "AND status = ? AND (updated_at, id) < (?, ?) ORDER BY updated_at DESC, id DESC LIMIT ?",
            ORGANIZATION_ID,
            PROJECT_ID,
            IncidentStatus.OPEN.name(),
            Timestamp.from(NOW),
            cursor.incidentId(),
            26
        );
    }

    @Test
    void repositorySanitizesDatabaseFailures() {
        JdbcTemplate jdbcTemplate = mock(JdbcTemplate.class);
        when(jdbcTemplate.query(
            any(String.class),
            ArgumentMatchers.<RowMapper<IncidentSummary>>any(),
            any(Object[].class)
        )).thenThrow(new DataAccessResourceFailureException("secret database endpoint"));
        JdbcIncidentListRepository jdbcRepository = new JdbcIncidentListRepository(jdbcTemplate);

        assertThatThrownBy(() -> jdbcRepository.list(
            ORGANIZATION_ID, PROJECT_ID, null, null, 26
        )).isInstanceOfSatisfying(PlatformProblemException.class, exception -> {
            assertThat(exception.code()).isEqualTo("incident.list-unavailable");
            assertThat(exception.getMessage()).doesNotContain("secret database endpoint");
        });
    }

    private void assertQuery(
        IncidentStatus status,
        IncidentListPageToken.Cursor cursor,
        String expectedSql,
        Object... expectedParameters
    ) {
        JdbcTemplate jdbcTemplate = mock(JdbcTemplate.class);
        JdbcIncidentListRepository jdbcRepository = new JdbcIncidentListRepository(jdbcTemplate);

        jdbcRepository.list(ORGANIZATION_ID, PROJECT_ID, status, cursor, 26);

        ArgumentCaptor<String> sql = ArgumentCaptor.forClass(String.class);
        ArgumentCaptor<Object[]> parameters = ArgumentCaptor.forClass(Object[].class);
        verify(jdbcTemplate).query(
            sql.capture(),
            ArgumentMatchers.<RowMapper<IncidentSummary>>any(),
            parameters.capture()
        );
        assertThat(sql.getValue()).contains(expectedSql);
        assertThat(parameters.getValue()).containsExactly(expectedParameters);
    }

    private IncidentSummary summary(String id, Instant updatedAt, long version) {
        return new IncidentSummary(
            UUID.fromString(id), "API unavailable", IncidentSeverity.SEV1,
            IncidentStatus.INVESTIGATING, updatedAt, version
        );
    }

    private OpsMindPrincipal principal() {
        return principal(Set.of("incident:read"));
    }

    private OpsMindPrincipal principal(Set<String> scopes) {
        return new OpsMindPrincipal(
            URI.create("https://idp.example.test/opsmind"),
            "operator-001", null, null, scopes
        );
    }
}
