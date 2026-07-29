package ai.opsmind.toolgateway.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.CyclicBarrier;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import ai.opsmind.toolgateway.config.ConnectorBulkheadProperties;
import ai.opsmind.toolgateway.domain.DenialCode;
import ai.opsmind.toolgateway.domain.ToolDeniedException;

import org.junit.jupiter.api.Test;

class TenantConnectorBulkheadTest {

    @Test
    void sharesOneAllowanceAcrossProjectsOfTheSameTenant() {
        TenantConnectorBulkhead bulkhead = new TenantConnectorBulkhead(properties(2, 1));
        TenantProjectScope firstProject = scope("1", "1");
        TenantProjectScope secondProject = scope(
            "1", "2"
        );
        TenantProjectScope otherTenant = scope(
            "2", "1"
        );

        TenantConnectorBulkhead.Permit first = bulkhead.acquire(firstProject);
        assertThatThrownBy(() -> bulkhead.acquire(secondProject))
            .isInstanceOfSatisfying(ToolDeniedException.class, exception -> {
                assertThat(exception.code()).isEqualTo(DenialCode.EXECUTION_BACKPRESSURE);
                assertThat(exception.getMessage()).doesNotContain(firstProject.tenantId().toString());
            });
        TenantConnectorBulkhead.Permit other = bulkhead.acquire(otherTenant);

        assertThat(bulkhead.trackedTenantCount()).isEqualTo(2);
        other.close();
        first.close();
        first.close();
        assertThat(bulkhead.trackedTenantCount()).isZero();
    }

    @Test
    void globalAllowanceStillBoundsDifferentTenants() {
        TenantConnectorBulkhead bulkhead = new TenantConnectorBulkhead(properties(1, 1));
        TenantConnectorBulkhead.Permit first = bulkhead.acquire(scope("1", "1"));

        assertThatThrownBy(() -> bulkhead.acquire(scope("2", "1")))
            .isInstanceOfSatisfying(ToolDeniedException.class, exception ->
                assertThat(exception.code()).isEqualTo(DenialCode.EXECUTION_BACKPRESSURE)
            );

        first.close();
        assertThat(bulkhead.trackedTenantCount()).isZero();
    }

    @Test
    void failedAdmissionDoesNotLeaveAnIdleTenantSlot() {
        TenantConnectorBulkhead bulkhead = new TenantConnectorBulkhead(properties(1, 1));
        TenantConnectorBulkhead.Permit first = bulkhead.acquire(scope("1", "1"));

        assertThatThrownBy(() -> bulkhead.acquire(scope("2", "1")))
            .isInstanceOf(ToolDeniedException.class);

        assertThat(bulkhead.trackedTenantCount()).isOne();
        first.close();
        assertThat(bulkhead.trackedTenantCount()).isZero();
    }

    @Test
    void finalReleaseAndSameTenantReacquireLeaveOneCleanRegistryEntry() throws Exception {
        TenantConnectorBulkhead bulkhead = new TenantConnectorBulkhead(properties(1, 1));
        TenantProjectScope scope = scope("1", "1");
        TenantConnectorBulkhead.Permit initial = bulkhead.acquire(scope);
        CyclicBarrier raceStart = new CyclicBarrier(2);
        CountDownLatch finalRelease = new CountDownLatch(1);

        try {
            try (ExecutorService callers = java.util.concurrent.Executors.newVirtualThreadPerTaskExecutor()) {
                Future<?> release = callers.submit(() -> {
                    raceStart.await(1, TimeUnit.SECONDS);
                    initial.close();
                    finalRelease.countDown();
                    return null;
                });
                Future<TenantConnectorBulkhead.Permit> reacquire = callers.submit(() -> {
                    raceStart.await(1, TimeUnit.SECONDS);
                    try {
                        return bulkhead.acquire(scope);
                    }
                    catch (ToolDeniedException expectedDuringRace) {
                        assertThat(expectedDuringRace.code()).isEqualTo(DenialCode.EXECUTION_BACKPRESSURE);
                        assertThat(finalRelease.await(1, TimeUnit.SECONDS)).isTrue();
                        return bulkhead.acquire(scope);
                    }
                });

                release.get(1, TimeUnit.SECONDS);
                TenantConnectorBulkhead.Permit reacquired = reacquire.get(1, TimeUnit.SECONDS);
                try {
                    assertThat(bulkhead.trackedTenantCount()).isOne();
                }
                finally {
                    reacquired.close();
                }
            }
        }
        finally {
            initial.close();
        }

        assertThat(bulkhead.trackedTenantCount()).isZero();
    }

    private static ConnectorBulkheadProperties properties(int global, int perTenant) {
        return new ConnectorBulkheadProperties(global, perTenant);
    }

    private static TenantProjectScope scope(String tenantSuffix, String projectSuffix) {
        return new TenantProjectScope(
            UUID.fromString("00000000-0000-0000-0000-00000000000" + tenantSuffix),
            UUID.fromString("00000000-0000-0000-0000-00000000000" + projectSuffix)
        );
    }
}
