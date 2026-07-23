package ai.opsmind.toolgateway.common.http;

public final class ResponseBodyTooLargeException extends RuntimeException {

    public ResponseBodyTooLargeException() {
        super("HTTP response exceeded its byte limit.");
    }
}
