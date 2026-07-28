package ai.opsmind.platform.investigation.workflow;

import java.util.HexFormat;

import ai.opsmind.platform.messaging.EventEnvelope;

import tools.jackson.core.JacksonException;
import tools.jackson.databind.ObjectMapper;

public final class InvestigationWorkflowStartEventCodec {

    private final ObjectMapper objectMapper;

    public InvestigationWorkflowStartEventCodec(ObjectMapper objectMapper) {
        this.objectMapper = objectMapper;
    }

    public DecodedStart decode(EventEnvelope event) {
        if (event == null
            || !InvestigationWorkflowStartEnvelopeFactory.EVENT_TYPE.equals(event.eventType())
            || !InvestigationWorkflowStartEnvelopeFactory.SCHEMA_VERSION.equals(event.schemaVersion())
            || !InvestigationWorkflowStartEnvelopeFactory.AGGREGATE_TYPE.equals(event.aggregateType())
            || event.aggregateSequence() != 1
            || !event.aggregateId().equals(event.correlationId())) {
            throw InvestigationWorkflowStartException.permanent(
                "workflow.event-contract-invalid", null
            );
        }
        try {
            InvestigationWorkflowStartRequest request = objectMapper.readValue(
                event.payloadJson(), InvestigationWorkflowStartRequest.class
            );
            if (!event.organizationId().equals(request.organizationId())
                || !event.aggregateId().equals(request.runId())) {
                throw InvestigationWorkflowStartException.permanent(
                    "workflow.event-identity-invalid", null
                );
            }
            return new DecodedStart(
                request,
                HexFormat.of().formatHex(event.payloadDigest())
            );
        }
        catch (InvestigationWorkflowStartException exception) {
            throw exception;
        }
        catch (JacksonException | IllegalArgumentException exception) {
            throw InvestigationWorkflowStartException.permanent(
                "workflow.event-payload-invalid", exception
            );
        }
    }

    public record DecodedStart(
        InvestigationWorkflowStartRequest request,
        String startPayloadDigest
    ) { }
}
