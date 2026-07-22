package ai.opsmind.platform.incident;

import java.net.URI;
import java.time.Instant;
import java.util.UUID;

import ai.opsmind.platform.common.api.IdempotencyKey;
import ai.opsmind.platform.common.api.OptimisticConcurrency;
import ai.opsmind.platform.common.api.PlatformProblemException;
import ai.opsmind.platform.identity.OpsMindPrincipal;
import ai.opsmind.platform.persistence.IdempotencyRepository;
import ai.opsmind.platform.persistence.IdempotencyRepository.IdempotencyClaim;

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionException;
import org.springframework.transaction.support.TransactionTemplate;

@Service
@ConditionalOnProperty(prefix = "opsmind.persistence", name = "enabled", havingValue = "true")
final class IncidentMutationService {

    private final TransactionTemplate transactions;
    private final IncidentAccessRepository accessRepository;
    private final IncidentRepository incidentRepository;
    private final IdempotencyRepository idempotencyRepository;
    private final IncidentDomainEventAppender eventAppender;
    private final IncidentJsonCodec jsonCodec;
    private final IncidentRuntimeValues runtimeValues;

    IncidentMutationService(
        PlatformTransactionManager transactionManager,
        IncidentAccessRepository accessRepository,
        IncidentRepository incidentRepository,
        IdempotencyRepository idempotencyRepository,
        IncidentDomainEventAppender eventAppender,
        IncidentJsonCodec jsonCodec,
        IncidentRuntimeValues runtimeValues
    ) {
        this.transactions = new TransactionTemplate(transactionManager);
        this.accessRepository = accessRepository;
        this.incidentRepository = incidentRepository;
        this.idempotencyRepository = idempotencyRepository;
        this.eventAppender = eventAppender;
        this.jsonCodec = jsonCodec;
        this.runtimeValues = runtimeValues;
    }

    IncidentOperationResult create(
        OpsMindPrincipal principal,
        UUID organizationId,
        UUID projectId,
        IdempotencyKey idempotencyKey,
        CreateIncidentRequest unvalidatedRequest,
        String externalTraceId
    ) {
        IncidentScopePolicy.require(principal, IncidentScopePolicy.WRITE_SCOPE);
        IncidentCommandValidator.requireCollectionIds(organizationId, projectId);
        CreateIncidentRequest request = IncidentCommandValidator.normalize(unvalidatedRequest);
        String traceId = IncidentCommandValidator.normalizeTrace(externalTraceId);
        try {
            return requireResult(transactions.execute(status -> createWithinTransaction(
                principal,
                organizationId,
                projectId,
                idempotencyKey,
                request,
                traceId
            )));
        }
        catch (TransactionException exception) {
            throw transactionUnavailable(exception);
        }
    }

    IncidentOperationResult transition(
        OpsMindPrincipal principal,
        UUID organizationId,
        UUID projectId,
        UUID incidentId,
        IdempotencyKey idempotencyKey,
        long expectedVersion,
        TransitionIncidentRequest unvalidatedRequest,
        String externalTraceId
    ) {
        IncidentScopePolicy.require(principal, IncidentScopePolicy.WRITE_SCOPE);
        IncidentCommandValidator.requireResourceIds(organizationId, projectId, incidentId);
        TransitionIncidentRequest request = IncidentCommandValidator.normalize(unvalidatedRequest);
        String traceId = IncidentCommandValidator.normalizeTrace(externalTraceId);
        try {
            return requireResult(transactions.execute(status -> transitionWithinTransaction(
                principal,
                organizationId,
                projectId,
                incidentId,
                idempotencyKey,
                expectedVersion,
                request,
                traceId
            )));
        }
        catch (TransactionException exception) {
            throw transactionUnavailable(exception);
        }
    }

    private IncidentOperationResult createWithinTransaction(
        OpsMindPrincipal principal,
        UUID organizationId,
        UUID projectId,
        IdempotencyKey idempotencyKey,
        CreateIncidentRequest request,
        String traceId
    ) {
        IncidentActor actor = accessRepository.requireAccess(
            principal, organizationId, projectId, IncidentAccessMode.MUTATE
        );
        byte[] requestDigest = IncidentRequestIdentity.create(actor.id(), organizationId, projectId, request);
        IdempotencyClaim claim = idempotencyRepository.claim(
            organizationId, actor.id(), idempotencyKey, requestDigest
        );
        IncidentOperationResult replay = replay(claim);
        if (replay != null) {
            return replay;
        }

        Instant occurredAt = runtimeValues.now();
        UUID incidentId = runtimeValues.newId();
        UUID operationId = runtimeValues.newId();
        UUID eventId = runtimeValues.newId();
        IncidentSnapshot incident = new IncidentSnapshot(
            incidentId, organizationId, projectId, request.title(), request.summary(), request.severity(),
            IncidentStatus.OPEN, null, null, actor.id(), actor.id(), occurredAt, occurredAt, 0
        );
        incidentRepository.insert(incident);
        eventAppender.append(new IncidentTimelineEvent(
            eventId, organizationId, projectId, incidentId, 0, IncidentTimelineEvent.CREATED,
            actor.id(), operationId, occurredAt, request.reason(), null, IncidentStatus.OPEN, null, null
        ), traceId);

        URI location = URI.create(IncidentRequestIdentity.incidentCollectionPath(organizationId, projectId)
            + "/" + incidentId);
        IncidentOperationResult result = new IncidentOperationResult(
            HttpStatus.CREATED.value(), jsonCodec.incidentBody(incident), location,
            OptimisticConcurrency.etag(0), operationId
        );
        idempotencyRepository.complete(
            organizationId, actor.id(), idempotencyKey, requestDigest,
            result.responseStatus(), jsonCodec.cache(result)
        );
        return result;
    }

    private IncidentOperationResult transitionWithinTransaction(
        OpsMindPrincipal principal,
        UUID organizationId,
        UUID projectId,
        UUID incidentId,
        IdempotencyKey idempotencyKey,
        long expectedVersion,
        TransitionIncidentRequest request,
        String traceId
    ) {
        IncidentActor actor = accessRepository.requireAccess(
            principal, organizationId, projectId, IncidentAccessMode.MUTATE
        );
        byte[] requestDigest = IncidentRequestIdentity.transition(
            actor.id(), organizationId, projectId, incidentId, expectedVersion, request
        );
        IdempotencyClaim claim = idempotencyRepository.claim(
            organizationId, actor.id(), idempotencyKey, requestDigest
        );
        IncidentOperationResult replay = replay(claim);
        if (replay != null) {
            return replay;
        }
        IncidentSnapshot current = incidentRepository.findForUpdate(organizationId, projectId, incidentId)
            .orElseThrow(IncidentRolePolicy::hiddenDenial);
        OptimisticConcurrency.requireCurrentVersion(current.version(), expectedVersion);
        IncidentStateMachine.ResolutionFields resolution = IncidentStateMachine.apply(
            current.status(), request.targetStatus(), current.rootCause(), current.resolutionSummary(),
            request.rootCause(), request.resolutionSummary()
        );

        Instant occurredAt = runtimeValues.now();
        UUID operationId = runtimeValues.newId();
        UUID eventId = runtimeValues.newId();
        IncidentSnapshot updated = incidentRepository.transition(
            organizationId, projectId, incidentId, expectedVersion, request.targetStatus(),
            resolution.rootCause(), resolution.resolutionSummary(), actor.id(), occurredAt
        );
        eventAppender.append(new IncidentTimelineEvent(
            eventId, organizationId, projectId, incidentId, updated.version(),
            IncidentTimelineEvent.STATUS_TRANSITIONED, actor.id(), operationId, occurredAt,
            request.reason(), current.status(), updated.status(), updated.rootCause(),
            updated.resolutionSummary()
        ), traceId);
        IncidentOperationResult result = new IncidentOperationResult(
            HttpStatus.OK.value(), jsonCodec.incidentBody(updated), null,
            OptimisticConcurrency.etag(updated.version()), operationId
        );
        idempotencyRepository.complete(
            organizationId, actor.id(), idempotencyKey, requestDigest,
            result.responseStatus(), jsonCodec.cache(result)
        );
        return result;
    }

    private IncidentOperationResult replay(IdempotencyClaim claim) {
        if (claim.disposition() == IdempotencyRepository.Disposition.REPLAY) {
            return jsonCodec.replay(claim.responseStatus(), claim.responseBody());
        }
        if (claim.disposition() == IdempotencyRepository.Disposition.IN_PROGRESS) {
            throw new PlatformProblemException(
                HttpStatus.CONFLICT,
                "idempotency.request-in-progress",
                "A request with this Idempotency-Key is still in progress."
            );
        }
        return null;
    }

    private IncidentOperationResult requireResult(IncidentOperationResult result) {
        if (result == null) {
            throw new IllegalStateException("Incident mutation transaction returned no result.");
        }
        return result;
    }

    private PlatformProblemException transactionUnavailable(TransactionException cause) {
        return new PlatformProblemException(
            HttpStatus.SERVICE_UNAVAILABLE,
            "incident.transaction-unavailable",
            "The incident transaction could not be completed.",
            cause
        );
    }
}
