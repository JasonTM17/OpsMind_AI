package ai.opsmind.platform.identity;

import java.util.UUID;

import ai.opsmind.platform.common.api.PlatformProblemException;

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.dao.DataAccessException;
import org.springframework.dao.EmptyResultDataAccessException;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionException;
import org.springframework.transaction.support.TransactionTemplate;

@Service
@ConditionalOnProperty(prefix = "opsmind.persistence", name = "enabled", havingValue = "true")
public final class PlatformUserStatusVerifier {

    private final JdbcTemplate jdbcTemplate;
    private final TransactionTemplate transactions;

    public PlatformUserStatusVerifier(
        JdbcTemplate jdbcTemplate,
        PlatformTransactionManager transactionManager
    ) {
        this.jdbcTemplate = jdbcTemplate;
        this.transactions = new TransactionTemplate(transactionManager);
        this.transactions.setReadOnly(true);
    }

    public UUID requireActive(OpsMindPrincipal principal) {
        if (principal == null) {
            throw denied("identity.principal-invalid", "The verified principal is invalid.");
        }
        try {
            UserStatus user = transactions.execute(status -> jdbcTemplate.queryForObject(
                "SELECT id, status FROM public.opsmind_resolve_user(?, ?)",
                (resultSet, rowNumber) -> new UserStatus(
                    resultSet.getObject("id", UUID.class),
                    resultSet.getString("status")
                ),
                principal.issuer().toString(),
                principal.subject()
            ));
            if (user == null || user.id() == null || !"active".equals(user.status())) {
                throw denied("identity.deprovisioned", "The verified principal is not active.");
            }
            return user.id();
        }
        catch (EmptyResultDataAccessException exception) {
            throw denied("identity.not-provisioned", "The verified principal is not provisioned.");
        }
        catch (PlatformProblemException exception) {
            throw exception;
        }
        catch (DataAccessException | TransactionException exception) {
            throw new PlatformProblemException(
                HttpStatus.SERVICE_UNAVAILABLE,
                "identity.authority-unavailable",
                "Identity authority is temporarily unavailable.",
                exception
            );
        }
    }

    private PlatformProblemException denied(String code, String detail) {
        return new PlatformProblemException(HttpStatus.FORBIDDEN, code, detail);
    }

    private record UserStatus(UUID id, String status) {
    }
}
