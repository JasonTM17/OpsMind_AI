package ai.opsmind.platform.common.api;

import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;

/** SHA-256 request identity used by idempotency and approval boundaries. */
public final class RequestDigest {

    public static final int LENGTH = 32;

    private RequestDigest() {
    }

    public static byte[] sha256(byte[] requestBytes) {
        if (requestBytes == null) {
            throw new IllegalArgumentException("Request bytes are required.");
        }
        try {
            return MessageDigest.getInstance("SHA-256").digest(requestBytes);
        }
        catch (NoSuchAlgorithmException exception) {
            throw new IllegalStateException("SHA-256 is required by the runtime.", exception);
        }
    }

    public static byte[] copyAndValidate(byte[] digest) {
        if (digest == null || digest.length != LENGTH) {
            throw new IllegalArgumentException("Request digest must be exactly 32 bytes.");
        }
        return digest.clone();
    }

    public static boolean constantTimeEquals(byte[] left, byte[] right) {
        if (left == null || right == null || left.length != right.length) {
            return false;
        }
        return MessageDigest.isEqual(left, right);
    }
}
