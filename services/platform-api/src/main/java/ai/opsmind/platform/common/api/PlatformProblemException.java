package ai.opsmind.platform.common.api;

import org.springframework.http.HttpStatus;

public final class PlatformProblemException extends RuntimeException {

    private final HttpStatus status;
    private final String code;

    public PlatformProblemException(HttpStatus status, String code, String safeDetail) {
        super(safeDetail);
        this.status = status;
        this.code = code;
    }

    public PlatformProblemException(
        HttpStatus status,
        String code,
        String safeDetail,
        Throwable cause
    ) {
        super(safeDetail, cause);
        this.status = status;
        this.code = code;
    }

    public HttpStatus status() {
        return status;
    }

    public String code() {
        return code;
    }
}
