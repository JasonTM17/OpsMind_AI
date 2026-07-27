package ai.opsmind.toolgateway.application;

import java.util.Collection;
import java.util.Map;
import java.util.UUID;
import java.util.function.Function;
import java.util.stream.Collectors;

import ai.opsmind.toolgateway.audit.ToolAuditWriter;
import ai.opsmind.toolgateway.audit.ToolExecutionProvenance;
import ai.opsmind.toolgateway.connectors.ToolConnector;
import ai.opsmind.toolgateway.domain.DenialCode;
import ai.opsmind.toolgateway.domain.EvidenceEnvelope;
import ai.opsmind.toolgateway.domain.ToolDeniedException;
import ai.opsmind.toolgateway.domain.ToolExecutionRequest;
import ai.opsmind.toolgateway.domain.ToolExecutionResponse;
import ai.opsmind.toolgateway.domain.ToolOutcome;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public final class ToolExecutionService {

    private static final Logger LOGGER = LoggerFactory.getLogger(ToolExecutionService.class);

    private final DelegatedCapabilityVerifier capabilityVerifier;
    private final ToolManifestRegistry manifestRegistry;
    private final PolicyEvaluator policyEvaluator;
    private final EvidenceNormalizer evidenceNormalizer;
    private final RequestDigester requestDigester;
    private final BoundedConnectorExecutor connectorExecutor;
    private final DurableToolExecutionCoordinator durability;
    private final ToolExecutionResponseFactory responseFactory;
    private final Map<String, ToolConnector> connectors;

    public ToolExecutionService(
        DelegatedCapabilityVerifier capabilityVerifier,
        ToolManifestRegistry manifestRegistry,
        PolicyEvaluator policyEvaluator,
        ExecutionReceiptStore receiptStore,
        EvidenceNormalizer evidenceNormalizer,
        ToolAuditWriter auditWriter,
        RequestDigester requestDigester,
        BoundedConnectorExecutor connectorExecutor,
        ToolExecutionTransactionRunner transactionRunner,
        Collection<ToolConnector> connectors
    ) {
        this.capabilityVerifier = capabilityVerifier;
        this.manifestRegistry = manifestRegistry;
        this.policyEvaluator = policyEvaluator;
        this.evidenceNormalizer = evidenceNormalizer;
        this.requestDigester = requestDigester;
        this.connectorExecutor = connectorExecutor;
        this.durability = new DurableToolExecutionCoordinator(
            receiptStore, auditWriter, transactionRunner
        );
        this.responseFactory = new ToolExecutionResponseFactory(auditWriter, transactionRunner);
        this.connectors = connectors.stream().collect(Collectors.toUnmodifiableMap(
            ToolConnector::id,
            Function.identity(),
            (first, duplicate) -> {
                throw new IllegalStateException("Duplicate connector identifier: " + first.id());
            }
        ));
    }

    public ToolExecutionResponse execute(String capabilityToken, ToolExecutionRequest request) {
        String requestDigest = RequestDigester.fallbackDigest("uncanonicalizable-tool-request");
        UUID executionId = request == null ? null : request.executionId();
        TenantProjectScope trustedScope = null;
        String capabilityId = null;
        String policyVersion = null;
        String manifestVersion = null;
        ToolExecutionProvenance provenance = null;
        ExecutionReceiptStore.Lease lease = null;
        String stage = "request-digest";
        try {
            requestDigest = request == null
                ? RequestDigester.fallbackDigest("null-request")
                : requestDigester.digest(request);
            stage = "capability-verification";
            VerifiedCapability capability = capabilityVerifier.verify(capabilityToken, request);
            trustedScope = TenantProjectScope.fromVerifiedCapability(capability, request);
            capabilityId = capability.capabilityId();
            policyVersion = capability.policyVersion();
            stage = "manifest-resolution";
            ToolManifest manifest = manifestRegistry.require(request);
            manifestVersion = manifest.manifestVersion();
            provenance = new ToolExecutionProvenance(
                manifest.tool(),
                manifest.action(),
                manifest.riskClass(),
                manifest.connectorId(),
                manifest.connectorProfile(),
                manifest.manifestByteDigest()
            );
            stage = "policy-evaluation";
            policyEvaluator.evaluate(request, manifest, capability);
            stage = "audit-availability";
            if (!durability.auditAvailable()) {
                throw denied(DenialCode.AUDIT_UNAVAILABLE, "Durable tool audit storage is unavailable.");
            }

            stage = "execution-claim";
            ExecutionReceiptStore.Claim claim = durability.claim(
                trustedScope,
                request,
                requestDigest
            );
            switch (claim.status()) {
                case REPLAY -> {
                    UUID auditId = durability.recordReplay(
                        trustedScope, executionId, requestDigest, capabilityId, manifestVersion,
                        provenance, responseFactory.evidenceDigest(claim.response()), policyVersion
                    );
                    return claim.response().asDuplicate(auditId);
                }
                case CONFLICT -> throw denied(
                    DenialCode.EXECUTION_CONFLICT,
                    "Execution identifier is already bound to another request."
                );
                case IN_PROGRESS -> throw denied(
                    DenialCode.EXECUTION_IN_PROGRESS,
                    "Execution is already in progress."
                );
                case UNAVAILABLE -> throw denied(
                    DenialCode.EXECUTION_STORE_UNAVAILABLE,
                    "Durable execution receipt storage is unavailable."
                );
                case CLAIMED -> {
                    lease = claim.lease();
                    if (lease == null) {
                        throw new IllegalStateException("Execution receipt lease is missing.");
                    }
                }
            }

            stage = "connector-selection";
            ToolConnector connector = connectors.get(manifest.connectorId());
            if (connector == null) {
                throw denied(DenialCode.ACTION_DISABLED, "Tool connector is unavailable.");
            }
            stage = "connector-execution";
            EvidenceEnvelope evidence = connectorExecutor.execute(
                () -> evidenceNormalizer.normalize(connector.execute(request, manifest), manifest, request),
                request,
                manifest
            );
            stage = "success-finalization";
            ToolExecutionResponse response = durability.finalizeSuccess(
                lease, evidence, capabilityId, manifestVersion, provenance, policyVersion
            );
            lease = null;
            return response;
        }
        catch (ToolDeniedException exception) {
            LOGGER.debug(
                "Tool execution denied. stage={} code={}",
                stage,
                exception.code().value()
            );
            return denialResponse(
                trustedScope, executionId, requestDigest, capabilityId, manifestVersion,
                provenance, policyVersion, exception.code()
            );
        }
        catch (RuntimeException exception) {
            LOGGER.debug(
                "Tool execution failed safely. stage={} failureType={}",
                stage,
                exception.getClass().getSimpleName()
            );
            return denialResponse(
                trustedScope, executionId, requestDigest, capabilityId, manifestVersion,
                provenance, policyVersion,
                "request-digest".equals(stage)
                    ? DenialCode.REQUEST_INVALID : DenialCode.CONNECTOR_FAILED
            );
        }
        finally {
            durability.abandon(lease);
        }
    }

    private ToolDeniedException denied(DenialCode code, String message) {
        return new ToolDeniedException(code, message);
    }

    private ToolExecutionResponse denialResponse(
        TenantProjectScope trustedScope,
        UUID executionId,
        String requestDigest,
        String capabilityId,
        String manifestVersion,
        ToolExecutionProvenance provenance,
        String policyVersion,
        DenialCode code
    ) {
        if (trustedScope == null) {
            return responseFactory.unverifiedDenial(executionId, requestDigest, code);
        }
        return responseFactory.scopedDenial(
            trustedScope, executionId, requestDigest, capabilityId, manifestVersion,
            provenance, policyVersion, code
        );
    }
}
