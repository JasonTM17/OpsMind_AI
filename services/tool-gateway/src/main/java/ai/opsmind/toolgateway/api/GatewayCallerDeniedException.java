package ai.opsmind.toolgateway.api;

public final class GatewayCallerDeniedException extends RuntimeException {

    public GatewayCallerDeniedException() {
        super("The workload is not authorized to invoke the Tool Gateway.");
    }
}
