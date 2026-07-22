package ai.opsmind.platform.evidence;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.lang.reflect.Modifier;
import java.util.List;
import java.util.UUID;

import ai.opsmind.platform.common.api.PlatformProblemException;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.support.TransactionSynchronizationManager;

import tools.jackson.databind.ObjectMapper;

class EvidenceRecordReaderTest {

    private static final UUID ORGANIZATION_ID = UUID.randomUUID();
    private static final UUID PROJECT_ID = UUID.randomUUID();
    private static final UUID INCIDENT_ID = UUID.randomUUID();
    private static final UUID RUN_ID = UUID.randomUUID();

    private final JdbcTemplate jdbcTemplate = mock(JdbcTemplate.class);
    private final EvidenceRecordReader reader = new EvidenceRecordReader(
        jdbcTemplate, new EvidenceContentCanonicalizer(new ObjectMapper())
    );

    @BeforeEach
    void beginAuthorizationTransaction() {
        TransactionSynchronizationManager.setActualTransactionActive(true);
    }

    @AfterEach
    void endAuthorizationTransaction() {
        TransactionSynchronizationManager.clear();
    }

    @Test
    void verifiesRunOwnershipEvenWhenNoEvidenceWasRequested() {
        when(jdbcTemplate.queryForObject(
            anyString(), eq(Boolean.class), eq(ORGANIZATION_ID), eq(PROJECT_ID),
            eq(INCIDENT_ID), eq(RUN_ID)
        )).thenReturn(false);

        assertThatThrownBy(() -> reader.resolve(
            ORGANIZATION_ID, PROJECT_ID, INCIDENT_ID, RUN_ID, List.of()
        )).isInstanceOf(PlatformProblemException.class)
            .satisfies(error -> assertThat(((PlatformProblemException) error).code())
                .isEqualTo("evidence.not-found"));
    }

    @Test
    void returnsAnEmptySetOnlyForAnAuthoritativeRun() {
        when(jdbcTemplate.queryForObject(
            anyString(), eq(Boolean.class), eq(ORGANIZATION_ID), eq(PROJECT_ID),
            eq(INCIDENT_ID), eq(RUN_ID)
        )).thenReturn(true);

        assertThat(reader.resolve(
            ORGANIZATION_ID, PROJECT_ID, INCIDENT_ID, RUN_ID, List.of()
        )).isEmpty();
    }

    @Test
    void remainsProxyableForRepositoryExceptionTranslation() {
        assertThat(Modifier.isFinal(EvidenceRecordReader.class.getModifiers())).isFalse();
    }
}
