package ai.opsmind.platform.identity;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.catchThrowableOfType;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.net.URI;
import java.sql.ResultSet;
import java.util.Set;
import java.util.UUID;

import ai.opsmind.platform.common.api.PlatformProblemException;

import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.transaction.CannotCreateTransactionException;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.TransactionStatus;
import org.springframework.transaction.TransactionSystemException;

class PlatformUserStatusVerifierTest {

    private final JdbcTemplate jdbcTemplate = mock(JdbcTemplate.class);
    private final PlatformTransactionManager transactionManager = mock(PlatformTransactionManager.class);
    private final PlatformUserStatusVerifier verifier = new PlatformUserStatusVerifier(
        jdbcTemplate,
        transactionManager
    );

    @Test
    void transactionAcquisitionFailureUsesAuthorityUnavailableContract() {
        var outage = new CannotCreateTransactionException("database offline");
        when(transactionManager.getTransaction(any(TransactionDefinition.class))).thenThrow(outage);

        PlatformProblemException problem = catchThrowableOfType(
            PlatformProblemException.class,
            () -> verifier.requireActive(principal())
        );

        assertAuthorityUnavailable(problem, outage);
    }

    @Test
    @SuppressWarnings({"rawtypes", "unchecked"})
    void transactionCommitFailureUsesAuthorityUnavailableContract() throws Exception {
        TransactionStatus status = mock(TransactionStatus.class);
        ResultSet resultSet = mock(ResultSet.class);
        UUID userId = UUID.fromString("10000000-0000-0000-0000-000000000001");
        when(transactionManager.getTransaction(any(TransactionDefinition.class))).thenReturn(status);
        when(resultSet.getObject("id", UUID.class)).thenReturn(userId);
        when(resultSet.getString("status")).thenReturn("active");
        doAnswer(invocation -> {
            RowMapper mapper = invocation.getArgument(1);
            return mapper.mapRow(resultSet, 0);
        }).when(jdbcTemplate).queryForObject(anyString(), any(RowMapper.class), any(Object[].class));
        var outage = new TransactionSystemException("commit failed");
        doThrow(outage).when(transactionManager).commit(status);

        PlatformProblemException problem = catchThrowableOfType(
            PlatformProblemException.class,
            () -> verifier.requireActive(principal())
        );

        assertAuthorityUnavailable(problem, outage);
    }

    private void assertAuthorityUnavailable(PlatformProblemException problem, Exception cause) {
        assertThat(problem.status()).isEqualTo(HttpStatus.SERVICE_UNAVAILABLE);
        assertThat(problem.code()).isEqualTo("identity.authority-unavailable");
        assertThat(problem.getMessage()).isEqualTo("Identity authority is temporarily unavailable.");
        assertThat(problem).hasCause(cause);
    }

    private OpsMindPrincipal principal() {
        return new OpsMindPrincipal(
            URI.create("https://idp.example.test/opsmind"),
            "operator-001",
            null,
            null,
            Set.of()
        );
    }
}
