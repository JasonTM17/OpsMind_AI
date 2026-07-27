package ai.opsmind.platform.incident;

import static org.assertj.core.api.Assertions.assertThat;

import java.sql.PreparedStatement;
import java.sql.SQLException;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.UUID;

import ai.opsmind.platform.tenancy.TenantContextSql;

import com.zaxxer.hikari.HikariConfig;
import com.zaxxer.hikari.HikariDataSource;

import org.springframework.jdbc.core.BatchPreparedStatementSetter;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.jdbc.support.JdbcTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

final class IncidentActivityTimelineEvidenceSupport {

    static final UUID ORGANIZATION_ID =
        UUID.fromString("70000000-0000-4000-8000-000000000001");
    static final UUID ACTOR_ID =
        UUID.fromString("70000000-0000-4000-8000-000000000002");
    static final UUID PROJECT_ID =
        UUID.fromString("70000000-0000-4000-8000-000000000003");
    static final UUID INCIDENT_ID =
        UUID.fromString("70000000-0000-4000-8000-000000000014");
    static final UUID INITIAL_EVENT_ID =
        UUID.fromString("70000000-0000-4000-8000-000000000015");
    static final UUID INITIAL_INVESTIGATION_EVENT_ID =
        UUID.fromString("70000000-0000-4000-8000-000000000018");
    static final Instant INITIAL_OCCURRED_AT = Instant.parse("2031-01-01T00:00:00Z");
    static final Instant INITIAL_INVESTIGATION_OCCURRED_AT =
        Instant.parse("2031-01-01T00:00:00.000001Z");

    private IncidentActivityTimelineEvidenceSupport() {
    }

    static Instant now() {
        return Instant.now().truncatedTo(ChronoUnit.MICROS);
    }

    static Instant after(Instant earlier) {
        Instant candidate = now();
        return candidate.isAfter(earlier)
            ? candidate
            : earlier.plus(1, ChronoUnit.MICROS);
    }

    static double milliseconds(long startedNanos) {
        return (System.nanoTime() - startedNanos) / 1_000_000.0;
    }

    static double p95(List<Double> samples) {
        assertThat(samples).isNotEmpty();
        List<Double> ordered = samples.stream().sorted().toList();
        int index = (int) Math.ceil(ordered.size() * 0.95) - 1;
        return ordered.get(Math.max(index, 0));
    }

    static final class Context implements AutoCloseable {

        private final IncidentActivityTimelineEvidenceSettings settings;
        private final HikariDataSource appDataSource;
        final JdbcTemplate appJdbc;
        final JdbcTemplate adminJdbc;
        final TransactionTemplate transactions;
        final TenantContextSql tenantContext;

        Context(String poolName) {
            settings = IncidentActivityTimelineEvidenceSettings.fromEnvironment();
            HikariConfig configuration = new HikariConfig();
            configuration.setPoolName(poolName);
            configuration.setJdbcUrl(settings.url());
            configuration.setUsername(settings.appUser());
            configuration.setPassword(settings.appPassword());
            configuration.setMaximumPoolSize(1);
            configuration.setMinimumIdle(1);
            appDataSource = new HikariDataSource(configuration);
            appJdbc = new JdbcTemplate(appDataSource);
            transactions = new TransactionTemplate(
                new JdbcTransactionManager(appDataSource)
            );
            tenantContext = new TenantContextSql(appJdbc);
            adminJdbc = new JdbcTemplate(new DriverManagerDataSource(
                settings.url(), settings.migrationUser(), settings.migrationPassword()
            ));
        }

        void assertRuntimeBoundary() {
            String boundary = transactions.execute(status -> {
                tenantContext.apply(ORGANIZATION_ID, ACTOR_ID);
                return appJdbc.queryForObject(
                    "SELECT current_user || ':' || session_user || ':' || "
                        + "public.opsmind_current_tenant_id()::text",
                    String.class
                );
            });
            assertThat(boundary).isEqualTo(
                settings.appUser() + ":" + settings.appUser() + ":" + ORGANIZATION_ID
            );
            System.out.println("V009RuntimeContext=" + boundary);
        }

        void assertHighCardinalityDistractors() {
            transactions.executeWithoutResult(status -> {
                tenantContext.apply(ORGANIZATION_ID, ACTOR_ID);
                long incidentTarget = count(
                    "incident_timeline_events",
                    "incident_id"
                );
                long investigationTarget = count(
                    "investigation_run_events",
                    "incident_id"
                );
                assertThat(incidentTarget).isEqualTo(50_000);
                assertThat(investigationTarget).isEqualTo(50_000);
                assertThat(count("incident_timeline_events", null))
                    .isGreaterThan(incidentTarget);
                assertThat(count("investigation_run_events", null))
                    .isGreaterThan(investigationTarget);
            });
            System.out.println("V009HighCardinalityDistractors=PASS");
        }

        private long count(String table, String incidentColumn) {
            String sql = "SELECT count(*) FROM " + table
                + " WHERE organization_id = ? AND project_id = ?";
            List<Object> parameters = new java.util.ArrayList<>(
                List.of(ORGANIZATION_ID, PROJECT_ID)
            );
            if (incidentColumn != null) {
                sql += " AND " + incidentColumn + " = ?";
                parameters.add(INCIDENT_ID);
            }
            Long result = appJdbc.queryForObject(
                sql,
                Long.class,
                parameters.toArray()
            );
            return result == null ? 0 : result;
        }

        void persistSamples(String kind, String phase, List<Double> samples) {
            adminJdbc.batchUpdate(
                "INSERT INTO phase_v009_samples "
                    + "(sample_kind, sample_phase, sample_no, duration_ms) "
                    + "VALUES (?, ?, ?, ?)",
                new BatchPreparedStatementSetter() {
                    @Override
                    public void setValues(PreparedStatement statement, int index)
                        throws SQLException {
                        statement.setString(1, kind);
                        statement.setString(2, phase);
                        statement.setInt(3, index + 1);
                        statement.setDouble(4, samples.get(index));
                    }

                    @Override
                    public int getBatchSize() {
                        return samples.size();
                    }
                }
            );
        }

        @Override
        public void close() {
            appDataSource.close();
        }
    }

}
