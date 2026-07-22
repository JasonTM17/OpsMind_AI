package ai.opsmind.platform.evidence;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Objects;
import java.util.UUID;

import ai.opsmind.platform.common.api.PlatformProblemException;

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.dao.DataAccessException;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.support.TransactionSynchronizationManager;

/** Resolves an ordered evidence set after the caller has bound authorized tenant context. */
@Repository
@ConditionalOnProperty(prefix = "opsmind.persistence", name = "enabled", havingValue = "true")
public final class EvidenceRecordReader {

    private static final int MAXIMUM_ITEMS = 200;

    private final JdbcTemplate jdbcTemplate;
    private final EvidenceContentCanonicalizer canonicalizer;

    public EvidenceRecordReader(
        JdbcTemplate jdbcTemplate,
        EvidenceContentCanonicalizer canonicalizer
    ) {
        this.jdbcTemplate = jdbcTemplate;
        this.canonicalizer = canonicalizer;
    }

    public List<ResolvedEvidenceRecord> resolve(
        UUID organizationId,
        UUID projectId,
        UUID incidentId,
        UUID runId,
        List<UUID> evidenceIds
    ) {
        if (!TransactionSynchronizationManager.isActualTransactionActive()) {
            throw new IllegalStateException("Evidence resolution requires an authorization transaction.");
        }
        if (organizationId == null || projectId == null || incidentId == null || runId == null
            || evidenceIds == null || evidenceIds.size() > MAXIMUM_ITEMS
            || evidenceIds.stream().anyMatch(Objects::isNull)
            || new HashSet<>(evidenceIds).size() != evidenceIds.size()) {
            throw invalidRequest();
        }

        try {
            if (!runExists(organizationId, projectId, incidentId, runId)) throw hidden();
            if (evidenceIds.isEmpty()) return List.of();
            List<ResolvedEvidenceRecord> records = query(
                organizationId, projectId, incidentId, runId, evidenceIds
            );
            if (records.size() != evidenceIds.size()) throw hidden();
            List<ResolvedEvidenceRecord> verified = new ArrayList<>(records.size());
            for (ResolvedEvidenceRecord record : records) {
                canonicalizer.verify(record.canonicalContent(), record.digest());
                verified.add(record);
            }
            return List.copyOf(verified);
        }
        catch (PlatformProblemException exception) {
            throw exception;
        }
        catch (DataAccessException exception) {
            throw unavailable(exception);
        }
        catch (IllegalArgumentException exception) {
            throw integrityFailure(exception);
        }
    }

    private boolean runExists(
        UUID organizationId, UUID projectId, UUID incidentId, UUID runId
    ) {
        Boolean exists = jdbcTemplate.queryForObject(
            "SELECT EXISTS (SELECT 1 FROM investigation_runs WHERE organization_id = ? "
                + "AND project_id = ? AND incident_id = ? AND run_id = ?)",
            Boolean.class, organizationId, projectId, incidentId, runId
        );
        return Boolean.TRUE.equals(exists);
    }

    private List<ResolvedEvidenceRecord> query(
        UUID organizationId, UUID projectId, UUID incidentId, UUID runId, List<UUID> evidenceIds
    ) {
        return jdbcTemplate.query(
            "SELECT evidence.evidence_id, evidence.run_id, "
                + "'sha256:' || encode(evidence.content_digest, 'hex') AS digest, "
                + "evidence.source_type, evidence.source_identity, evidence.target_identity, "
                + "evidence.observed_at, evidence.trust_class, evidence.canonical_content, "
                + "evidence.truncated FROM jsonb_array_elements_text(CAST(? AS jsonb)) "
                + "WITH ORDINALITY requested(evidence_id, ordinal) "
                + "JOIN evidence_records evidence ON evidence.evidence_id = requested.evidence_id::uuid "
                + "AND evidence.organization_id = ? AND evidence.project_id = ? "
                + "AND evidence.incident_id = ? AND evidence.run_id = ? "
                + "AND evidence.lifecycle_state = 'AVAILABLE' ORDER BY requested.ordinal",
            (resultSet, rowNumber) -> new ResolvedEvidenceRecord(
                resultSet.getObject("evidence_id", UUID.class),
                resultSet.getObject("run_id", UUID.class), resultSet.getString("digest"),
                resultSet.getString("source_type"), resultSet.getString("source_identity"),
                resultSet.getString("target_identity"), resultSet.getTimestamp("observed_at").toInstant(),
                resultSet.getString("trust_class"), resultSet.getString("canonical_content"),
                resultSet.getBoolean("truncated")
            ),
            jsonArray(evidenceIds), organizationId, projectId, incidentId, runId
        );
    }

    private String jsonArray(List<UUID> evidenceIds) {
        return evidenceIds.stream().map(id -> "\"" + id + "\"")
            .collect(java.util.stream.Collectors.joining(",", "[", "]"));
    }

    private PlatformProblemException invalidRequest() {
        return new PlatformProblemException(
            HttpStatus.BAD_REQUEST, "evidence.request-invalid", "Evidence identifiers are invalid."
        );
    }

    private PlatformProblemException hidden() {
        return new PlatformProblemException(
            HttpStatus.NOT_FOUND, "evidence.not-found", "Evidence was not found or is not visible."
        );
    }

    private PlatformProblemException unavailable(Throwable cause) {
        return new PlatformProblemException(
            HttpStatus.SERVICE_UNAVAILABLE, "evidence.persistence-unavailable",
            "Evidence persistence is temporarily unavailable.", cause
        );
    }

    private PlatformProblemException integrityFailure(Throwable cause) {
        return new PlatformProblemException(
            HttpStatus.SERVICE_UNAVAILABLE, "evidence.integrity-invalid",
            "Stored evidence failed integrity validation.", cause
        );
    }
}
