package ai.opsmind.toolgateway.application;

import java.util.Collection;
import java.util.Map;
import java.util.UUID;
import java.util.function.Function;
import java.util.stream.Collectors;

import ai.opsmind.toolgateway.audit.ToolAuditWriter;
import ai.opsmind.toolgateway.connectors.ToolConnector;
import ai.opsmind.toolgateway.domain.DenialCode;
import ai.opsmind.toolgateway.domain.EvidenceEnvelope;
import ai.opsmind.toolgateway.domain.ToolDeniedException;
import ai.opsmind.toolgateway.domain.ToolExecutionRequest;
import ai.opsmind.toolgateway.domain.ToolExecutionResponse;
import ai.opsmind.toolgateway.domain.ToolOutcome;

public final class ToolExecutionService {

    private final DelegatedCapabilityVerifier capabilityVerifier;
    private final ToolManifestRegistry manifestRegistry;
    private final PolicyEvaluator policyEvaluator;
    private final ExecutionReceiptStore receiptStore;
    private final EvidenceNormalizer evidenceNormalizer;
    private final ToolAuditWriter auditWriter;
    private final RequestDigester requestDigester;
    private final BoundedConnectorExecutor connectorExecutor;
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
        Collection<ToolConnector> connectors
    ) {
        this.capabilityVerifier = capabilityVerifier;
        this.manifestRegistry = manifestRegistry;
        this.policyEvaluator = policyEvaluator;
        this.receiptStore = receiptStore;
        this.evidenceNormalizer = evidenceNormalizer;
        this.auditWriter = auditWriter;
        this.requestDigester = requestDigester;
        this.connectorExecutor = connectorExecutor;
        this.responseFactory = new ToolExecutionResponseFactory(auditWriter);
        this.connectors = connectors.stream().collect(Collectors.toUnmodifiableMap(
            ToolConnector::id,
            Function.identity(),
            (first, duplicate) -> {
                throw new IllegalStateException("Duplicate connector identifier: " + first.id());
            }
        ));
    }

    public ToolExecutionResponse execute(String capabilityToken, ToolExecutionRequest request) {
        String requestDigest = request == null
            ? RequestDigester.fallbackDigest("null-request") : requestDigester.digest(request);
        UUID executionId = request == null ? null : request.executionId();
        String capabilityId = null;
        String policyVersion = null;
        String manifestVersion = null;
        boolean claimed = false;
        try {
            VerifiedCapability capability = capabilityVerifier.verify(capabilityToken, request);
            capabilityId = capability.capabilityId();
            policyVersion = capability.policyVersion();
            ToolManifest manifest = manifestRegistry.require(request);
            manifestVersion = manifest.manifestVersion();
            policyEvaluator.evaluate(request, manifest, capability);
            if (!auditWriter.available()) {
                throw denied(DenialCode.AUDIT_UNAVAILABLE, "Durable tool audit storage is unavailable.");
            }

            ExecutionReceiptStore.Claim claim = receiptStore.claim(executionId, requestDigest);
            switch (claim.status()) {
                case REPLAY -> {
                    UUID auditId = auditWriter.record(
                        executionId,
                        ToolOutcome.DUPLICATE,
                        requestDigest,
                        capabilityId,
                        manifestVersion,
                        responseFactory.evidenceDigest(claim.response()),
                        policyVersion,
                        null
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
                case CLAIMED -> claimed = true;
            }

            ToolConnector connector = connectors.get(manifest.connectorId());
            if (connector == null) {
                throw denied(DenialCode.ACTION_DISABLED, "Tool connector is unavailable.");
            }
            EvidenceEnvelope evidence = connectorExecutor.execute(
                () -> evidenceNormalizer.normalize(connector.execute(request, manifest), manifest, request),
                request,
                manifest
            );
            UUID auditId = auditWriter.record(
                executionId,
                ToolOutcome.SUCCEEDED,
                requestDigest,
                capabilityId,
                manifestVersion,
                evidence.contentDigest(),
                policyVersion,
                null
            );
            ToolExecutionResponse response = new ToolExecutionResponse(
                executionId,
                ToolOutcome.SUCCEEDED,
                java.util.List.of(evidence),
                null,
                auditId,
                requestDigest,
                manifestVersion,
                evidence.source() + "/" + evidence.connectorVersion(),
                evidence.redactedFields(),
                evidence.truncated(),
                false
            );
            receiptStore.complete(executionId, requestDigest, response);
            claimed = false;
            return response;
        }
        catch (ToolDeniedException exception) {
            return responseFactory.denial(
                executionId,
                requestDigest,
                capabilityId,
                manifestVersion,
                policyVersion,
                exception.code()
            );
        }
        catch (RuntimeException exception) {
            return responseFactory.denial(
                executionId,
                requestDigest,
                capabilityId,
                manifestVersion,
                policyVersion,
                DenialCode.CONNECTOR_FAILED
            );
        }
        finally {
            if (claimed && executionId != null) receiptStore.abandon(executionId, requestDigest);
        }
    }

    private ToolDeniedException denied(DenialCode code, String message) {
        return new ToolDeniedException(code, message);
    }
}
