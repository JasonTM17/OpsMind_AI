package ai.opsmind.platform.evidence.artifact.storage;

import java.net.URI;
import java.time.Duration;
import java.util.Locale;
import java.util.regex.Pattern;

import org.springframework.boot.context.properties.ConfigurationProperties;

/** Non-secret, environment-owned settings for the optional evidence object store. */
@ConfigurationProperties(prefix = "opsmind.evidence.artifact.storage")
public record EvidenceArtifactStorageProperties(
    boolean enabled,
    URI endpoint,
    boolean allowLoopbackCleartext,
    String region,
    String bucket,
    boolean pathStyleAccess,
    String expectedBucketOwner,
    String kmsKeyId,
    String encryptionProfile,
    long maximumObjectBytes,
    Duration connectTimeout,
    Duration socketTimeout,
    Duration apiCallAttemptTimeout,
    Duration apiCallTimeout,
    int maximumConnections,
    Duration uploadLeaseDuration
) {
    static final long MAXIMUM_SUPPORTED_OBJECT_BYTES = 5_000_000_000L;

    private static final Pattern ACCOUNT_ID = Pattern.compile("[0-9]{12}");
    private static final Pattern BUCKET = Pattern.compile("[a-z0-9][a-z0-9.-]{1,61}[a-z0-9]");
    private static final Pattern KMS_KEY = Pattern.compile("[A-Za-z0-9][A-Za-z0-9._:/=,@+\\-]{0,2047}");
    private static final Pattern PROFILE = Pattern.compile("[A-Za-z0-9][A-Za-z0-9._:/\\-]{0,127}");
    private static final Pattern REGION = Pattern.compile("[a-z]{2}(?:-[a-z0-9]+)+-[0-9]+");

    public EvidenceArtifactStorageProperties {
        region = trim(region);
        bucket = trim(bucket);
        expectedBucketOwner = trim(expectedBucketOwner);
        kmsKeyId = trim(kmsKeyId);
        encryptionProfile = trim(encryptionProfile);
        maximumObjectBytes = maximumObjectBytes == 0
            ? MAXIMUM_SUPPORTED_OBJECT_BYTES : maximumObjectBytes;
        connectTimeout = defaultDuration(connectTimeout, Duration.ofSeconds(2));
        socketTimeout = defaultDuration(socketTimeout, Duration.ofSeconds(10));
        apiCallAttemptTimeout = defaultDuration(apiCallAttemptTimeout, Duration.ofSeconds(20));
        apiCallTimeout = defaultDuration(apiCallTimeout, Duration.ofSeconds(30));
        maximumConnections = maximumConnections == 0 ? 10 : maximumConnections;
        uploadLeaseDuration = defaultDuration(uploadLeaseDuration, Duration.ofMinutes(2));
    }

    /** Validates only an enabled adapter so disabled deployments need no storage details. */
    public void validateForEnablement() {
        if (!enabled) return;
        if (!validEndpoint(endpoint) || !REGION.matcher(region).matches() || !validBucket(bucket)) {
            throw new IllegalStateException("Evidence artifact storage endpoint settings are invalid.");
        }
        if (!expectedBucketOwner.isBlank() && !ACCOUNT_ID.matcher(expectedBucketOwner).matches()) {
            throw new IllegalStateException("Evidence artifact storage owner setting is invalid.");
        }
        if (!KMS_KEY.matcher(kmsKeyId).matches() || !PROFILE.matcher(encryptionProfile).matches()) {
            throw new IllegalStateException("Evidence artifact storage encryption settings are invalid.");
        }
        if (maximumObjectBytes < 1 || maximumObjectBytes > MAXIMUM_SUPPORTED_OBJECT_BYTES) {
            throw new IllegalStateException("Evidence artifact storage object limit is invalid.");
        }
        if (!between(connectTimeout, Duration.ofMillis(100), Duration.ofSeconds(30))
            || !between(socketTimeout, Duration.ofMillis(100), Duration.ofMinutes(5))
            || !between(apiCallAttemptTimeout, Duration.ofSeconds(1), Duration.ofMinutes(10))
            || !between(apiCallTimeout, Duration.ofSeconds(2), Duration.ofMinutes(15))
            || !between(uploadLeaseDuration, Duration.ofSeconds(5), Duration.ofHours(1))
            || apiCallAttemptTimeout.compareTo(apiCallTimeout) >= 0
            || apiCallTimeout.compareTo(uploadLeaseDuration) >= 0
            || maximumConnections < 1 || maximumConnections > 1_000) {
            throw new IllegalStateException("Evidence artifact storage timeout settings are invalid.");
        }
    }

    @Override
    public String toString() {
        return "EvidenceArtifactStorageProperties[enabled=" + enabled + "]";
    }

    private boolean validEndpoint(URI value) {
        if (value == null || !value.isAbsolute() || value.getHost() == null
            || value.getRawUserInfo() != null || value.getRawQuery() != null
            || value.getRawFragment() != null || invalidPort(value.getPort())) {
            return false;
        }
        if ("https".equalsIgnoreCase(value.getScheme())) return true;
        return allowLoopbackCleartext && "http".equalsIgnoreCase(value.getScheme())
            && literalLoopback(value.getHost());
    }

    private static boolean validBucket(String value) {
        return BUCKET.matcher(value).matches() && !value.contains("..")
            && !value.matches("[0-9]{1,3}(?:\\.[0-9]{1,3}){3}");
    }

    private static boolean literalLoopback(String host) {
        String value = host.toLowerCase(Locale.ROOT);
        return value.equals("127.0.0.1") || value.equals("::1") || value.equals("[::1]");
    }

    private static boolean invalidPort(int port) {
        return port != -1 && (port < 1 || port > 65_535);
    }

    private static boolean between(Duration value, Duration minimum, Duration maximum) {
        return value != null && !value.isNegative() && !value.isZero()
            && value.compareTo(minimum) >= 0 && value.compareTo(maximum) <= 0;
    }

    private static String trim(String value) {
        return value == null ? "" : value.trim();
    }

    private static Duration defaultDuration(Duration value, Duration fallback) {
        return value == null ? fallback : value;
    }
}
