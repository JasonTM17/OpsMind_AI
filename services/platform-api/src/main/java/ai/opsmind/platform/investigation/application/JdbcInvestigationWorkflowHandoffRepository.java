package ai.opsmind.platform.investigation.application;

import java.util.Objects;
import java.util.Optional;

import ai.opsmind.platform.common.api.PlatformProblemException;
import ai.opsmind.platform.common.api.RequestDigest;
import ai.opsmind.platform.incident.AuthorizedIncidentAnalysisEvidence;
import ai.opsmind.platform.investigation.domain.InvestigationCommand;
import ai.opsmind.platform.investigation.domain.InvestigationStateMachine;
import ai.opsmind.platform.investigation.workflow.InvestigationWorkflowHandoffRepository;
import ai.opsmind.platform.investigation.workflow.InvestigationWorkflowProperties;
import ai.opsmind.platform.investigation.workflow.InvestigationWorkflowStartEnvelopeFactory;
import ai.opsmind.platform.investigation.workflow.InvestigationWorkflowStartEnvelopeFactory.PreparedStart;
import ai.opsmind.platform.investigation.application.JdbcInvestigationWorkflowBindingStore.StoredBinding;
import ai.opsmind.platform.messaging.OutboxRepository;
import ai.opsmind.platform.tenancy.TenantContextSql;

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Profile;
import org.springframework.dao.DataAccessException;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionException;
import org.springframework.transaction.support.TransactionTemplate;

@Repository
@Profile("persistence")
@ConditionalOnProperty(prefix = "opsmind.persistence", name = "enabled", havingValue = "true")
@ConditionalOnProperty(prefix = "opsmind.investigation", name = "store", havingValue = "postgres")
@ConditionalOnProperty(prefix = "opsmind.investigation", name = "execution-mode", havingValue = "temporal")
public final class JdbcInvestigationWorkflowHandoffRepository
    implements InvestigationWorkflowHandoffRepository {

    private final JdbcInvestigationWorkflowBindingStore bindingStore;
    private final TenantContextSql tenantContextSql;
    private final TransactionTemplate transactions;
    private final InvestigationInitialRunWriter initialRunWriter;
    private final InvestigationRunStore runStore;
    private final OutboxRepository outboxRepository;
    private final InvestigationWorkflowStartEnvelopeFactory envelopeFactory;
    private final InvestigationWorkflowProperties properties;

    public JdbcInvestigationWorkflowHandoffRepository(
        JdbcTemplate jdbcTemplate,
        TenantContextSql tenantContextSql,
        PlatformTransactionManager transactionManager,
        InvestigationInitialRunWriter initialRunWriter,
        InvestigationRunStore runStore,
        OutboxRepository outboxRepository,
        InvestigationWorkflowStartEnvelopeFactory envelopeFactory,
        InvestigationWorkflowProperties properties
    ) {
        this.bindingStore = new JdbcInvestigationWorkflowBindingStore(jdbcTemplate);
        this.tenantContextSql = tenantContextSql;
        this.transactions = new TransactionTemplate(transactionManager);
        this.initialRunWriter = initialRunWriter;
        this.runStore = runStore;
        this.outboxRepository = outboxRepository;
        this.envelopeFactory = envelopeFactory;
        this.properties = properties;
    }

    @Override
    public Optional<InvestigationStateMachine.State> loadExisting(
        InvestigationCommand.Start command,
        AuthorizedIncidentAnalysisEvidence authorizedIncident
    ) {
        PreparedStart prepared = envelopeFactory.prepare(command, authorizedIncident, properties);
        try {
            return Objects.requireNonNull(transactions.execute(status -> {
                tenantContextSql.apply(command.organizationId(), command.actorId());
                Optional<StoredBinding> binding = bindingStore.find(command);
                if (binding.isEmpty()) return Optional.empty();
                return Optional.of(loadIdempotentRun(command, prepared, binding.get()));
            }), "Investigation handoff lookup returned no result.");
        }
        catch (PlatformProblemException exception) {
            throw exception;
        }
        catch (DataAccessException | TransactionException exception) {
            throw persistenceUnavailable(exception);
        }
    }

    @Override
    public InvestigationStateMachine.State createOrLoad(
        InvestigationCommand.Start command,
        AuthorizedIncidentAnalysisEvidence authorizedIncident
    ) {
        PreparedStart prepared = envelopeFactory.prepare(command, authorizedIncident, properties);
        try {
            return Objects.requireNonNull(transactions.execute(status -> {
                tenantContextSql.apply(command.organizationId(), command.actorId());
                if (bindingStore.hasUnboundNonterminalRun()) {
                    throw workflowCutoverRequired();
                }
                InvestigationStateMachine.Step initial = InvestigationStateMachine.start(command);
                if (!initialRunWriter.createIfAbsentInCurrentTransaction(initial)) {
                    StoredBinding binding = bindingStore.find(command)
                        .orElseThrow(this::workflowCutoverRequired);
                    return loadIdempotentRun(command, prepared, binding);
                }
                return createRunBindingAndOutbox(command, prepared, initial);
            }), "Investigation handoff transaction returned no state.");
        }
        catch (PlatformProblemException exception) {
            throw exception;
        }
        catch (DataAccessException | TransactionException exception) {
            throw persistenceUnavailable(exception);
        }
    }

    private InvestigationStateMachine.State createRunBindingAndOutbox(
        InvestigationCommand.Start command,
        PreparedStart prepared,
        InvestigationStateMachine.Step initial
    ) {
        if (!bindingStore.insert(command, prepared)) {
            throw persistenceUnavailable(null);
        }
        outboxRepository.append(prepared.event());
        return initial.state();
    }

    private InvestigationStateMachine.State loadIdempotentRun(
        InvestigationCommand.Start command,
        PreparedStart prepared,
        StoredBinding binding
    ) {
        if (!RequestDigest.constantTimeEquals(
            binding.clientRequestDigest(),
            prepared.clientRequestDigest()
        )) {
            throw runConflict();
        }
        if ("REJECTED".equals(binding.status())) {
            throw workflowStartRejected();
        }
        if (!"PENDING".equals(binding.status()) && !"STARTED".equals(binding.status())) {
            throw persistenceUnavailable(null);
        }
        return runStore.require(command.organizationId(), command.actorId(), command.runId());
    }

    private PlatformProblemException workflowCutoverRequired() {
        return new PlatformProblemException(
            HttpStatus.CONFLICT,
            "investigation.workflow-cutover-required",
            "The investigation predates durable workflow admission and requires operator reconciliation."
        );
    }

    private PlatformProblemException runConflict() {
        return new PlatformProblemException(
            HttpStatus.CONFLICT,
            "investigation.run-conflict",
            "The investigation run identifier is already bound to a different request."
        );
    }

    private PlatformProblemException workflowStartRejected() {
        return new PlatformProblemException(
            HttpStatus.CONFLICT,
            "investigation.workflow-start-rejected",
            "The durable investigation start was rejected and requires operator reconciliation."
        );
    }

    private PlatformProblemException persistenceUnavailable(Throwable cause) {
        return new PlatformProblemException(
            HttpStatus.SERVICE_UNAVAILABLE,
            "investigation.workflow-persistence-unavailable",
            "Durable investigation admission is temporarily unavailable.",
            cause
        );
    }

}
