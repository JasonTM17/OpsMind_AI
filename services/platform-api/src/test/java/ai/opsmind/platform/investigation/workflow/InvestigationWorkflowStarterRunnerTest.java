package ai.opsmind.platform.investigation.workflow;

import static org.assertj.core.api.Assertions.assertThatCode;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.time.Duration;
import java.util.List;
import java.util.UUID;

import org.junit.jupiter.api.Test;
import org.mockito.InOrder;

class InvestigationWorkflowStarterRunnerTest {

    @Test
    void durablePendingTenantsContinueDispatchingWithoutAnAdmissionProbe() {
        UUID firstTenant = UUID.randomUUID();
        UUID secondTenant = UUID.randomUUID();
        InvestigationWorkflowStartTenantScheduler scheduler =
            mock(InvestigationWorkflowStartTenantScheduler.class);
        InvestigationWorkflowStartDispatcher dispatcher =
            mock(InvestigationWorkflowStartDispatcher.class);
        when(scheduler.listReadyTenants(10))
            .thenReturn(List.of(firstTenant, secondTenant));
        InvestigationWorkflowStarterRunner runner = new InvestigationWorkflowStarterRunner(
            scheduler, dispatcher, properties()
        );

        runner.runOnce();

        InOrder order = inOrder(dispatcher);
        order.verify(dispatcher).dispatchTenant(firstTenant);
        order.verify(dispatcher).dispatchTenant(secondTenant);
    }

    @Test
    void oneTenantFailureDoesNotStarveTheRemainingReadyTenants() {
        UUID firstTenant = UUID.randomUUID();
        UUID secondTenant = UUID.randomUUID();
        InvestigationWorkflowStartTenantScheduler scheduler =
            mock(InvestigationWorkflowStartTenantScheduler.class);
        InvestigationWorkflowStartDispatcher dispatcher =
            mock(InvestigationWorkflowStartDispatcher.class);
        when(scheduler.listReadyTenants(10))
            .thenReturn(List.of(firstTenant, secondTenant));
        doThrow(new IllegalStateException("synthetic"))
            .when(dispatcher).dispatchTenant(firstTenant);
        InvestigationWorkflowStarterRunner runner = new InvestigationWorkflowStarterRunner(
            scheduler, dispatcher, properties()
        );

        assertThatCode(runner::runOnce).doesNotThrowAnyException();

        InOrder order = inOrder(dispatcher);
        order.verify(dispatcher).dispatchTenant(firstTenant);
        order.verify(dispatcher).dispatchTenant(secondTenant);
    }

    @Test
    void tenantEnumerationFailureDoesNotEscapeTheScheduledBoundary() {
        InvestigationWorkflowStartTenantScheduler scheduler =
            mock(InvestigationWorkflowStartTenantScheduler.class);
        InvestigationWorkflowStartDispatcher dispatcher =
            mock(InvestigationWorkflowStartDispatcher.class);
        when(scheduler.listReadyTenants(10))
            .thenThrow(new IllegalStateException("synthetic"));
        InvestigationWorkflowStarterRunner runner = new InvestigationWorkflowStarterRunner(
            scheduler, dispatcher, properties()
        );

        assertThatCode(runner::runOnce).doesNotThrowAnyException();
    }

    private InvestigationWorkflowStarterProperties properties() {
        return new InvestigationWorkflowStarterProperties(
            true,
            Duration.ofSeconds(1),
            Duration.ofSeconds(30),
            Duration.ofSeconds(5),
            Duration.ofHours(1),
            Duration.ofSeconds(1),
            Duration.ofSeconds(30),
            5,
            10,
            10
        );
    }
}
