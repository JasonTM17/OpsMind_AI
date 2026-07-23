package ai.opsmind.platform.investigation.application;

import ai.opsmind.platform.common.api.PlatformProblemException;
import ai.opsmind.platform.investigation.domain.InvestigationStateMachine;

import org.springframework.dao.EmptyResultDataAccessException;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;

final class JdbcInvestigationRunReader {

    private final JdbcTemplate jdbcTemplate;
    private final InvestigationRunSqlMapper sqlMapper;

    JdbcInvestigationRunReader(
        JdbcTemplate jdbcTemplate,
        InvestigationRunSqlMapper sqlMapper
    ) {
        this.jdbcTemplate = jdbcTemplate;
        this.sqlMapper = sqlMapper;
    }

    InvestigationStateMachine.State require(String predicate, Object... arguments) {
        try {
            return jdbcTemplate.queryForObject(
                "SELECT " + InvestigationRunSqlMapper.STATE_COLUMNS + " FROM investigation_runs "
                    + predicate,
                sqlMapper::mapState,
                arguments
            );
        }
        catch (EmptyResultDataAccessException exception) {
            throw new PlatformProblemException(
                HttpStatus.NOT_FOUND,
                "investigation.run-not-found",
                "The investigation run was not found."
            );
        }
    }
}
