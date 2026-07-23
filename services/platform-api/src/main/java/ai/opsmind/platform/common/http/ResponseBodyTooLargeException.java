package ai.opsmind.platform.common.http;

import java.io.IOException;

public final class ResponseBodyTooLargeException extends IOException {

    public ResponseBodyTooLargeException() {
        super("HTTP response exceeded the configured byte limit.");
    }
}
