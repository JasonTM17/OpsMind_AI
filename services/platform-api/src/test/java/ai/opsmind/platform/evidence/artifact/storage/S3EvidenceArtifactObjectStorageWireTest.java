package ai.opsmind.platform.evidence.artifact.storage;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Duration;
import java.time.Instant;
import java.util.Base64;
import java.util.HexFormat;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import ai.opsmind.platform.evidence.artifact.EvidenceArtifactDigest;
import org.junit.jupiter.api.Test;

import software.amazon.awssdk.auth.credentials.AnonymousCredentialsProvider;
import software.amazon.awssdk.awscore.retry.AwsRetryStrategy;
import software.amazon.awssdk.core.client.config.ClientOverrideConfiguration;
import software.amazon.awssdk.http.apache5.Apache5HttpClient;
import software.amazon.awssdk.regions.Region;
import software.amazon.awssdk.services.s3.S3Client;

class S3EvidenceArtifactObjectStorageWireTest {

    private static final byte[] BODY = "durable evidence".getBytes(StandardCharsets.UTF_8);
    private static final String KMS_KEY = "arn:aws:kms:ap-southeast-1:123456789012:key/key-1";
    private static final EvidenceArtifactDigest DIGEST = new EvidenceArtifactDigest(
        "sha256:" + HexFormat.of().formatHex(sha256(BODY))
    );
    private static final ArtifactObjectExpectation EXPECTATION = new ArtifactObjectExpectation(
        java.util.UUID.fromString("730c8fea-5479-46f8-aab3-d3b60f871c37"),
        "artifact/730c8fea",
        DIGEST,
        BODY.length
    );

    @Test
    void sendsVerifiedContentThroughTheRealS3Wire() throws IOException {
        AtomicReference<CapturedRequest> captured = new AtomicReference<>();
        try (LoopbackServer server = LoopbackServer.success(captured)) {
            EvidenceArtifactStorageProperties properties = properties(server.endpoint());
            try (
                S3Client client = client(properties);
                S3EvidenceArtifactObjectStorage storage =
                    new S3EvidenceArtifactObjectStorage(client, properties)
            ) {
                ArtifactObjectStored stored = storage.putIfAbsent(
                    EXPECTATION,
                    source(),
                    Instant.now().plusSeconds(30)
                );

                CapturedRequest request = captured.get();
                assertThat(request.method()).isEqualTo("PUT");
                assertThat(request.path()).isEqualTo("/evidence-artifacts/artifact/730c8fea");
                assertThat(request.ifNoneMatch()).isEqualTo("*");
                assertThat(request.checksum()).isEqualTo(encodedDigest());
                assertThat(request.encryption()).isEqualTo("aws:kms");
                assertThat(request.kmsKey()).isEqualTo(KMS_KEY);
                assertThat(request.body()).isEqualTo(BODY);
                assertThat(stored.digest()).isEqualTo(DIGEST);
            }
        }
    }

    @Test
    void doesNotRetryARejectedWirePut() throws IOException {
        AtomicInteger requestCount = new AtomicInteger();
        try (LoopbackServer server = LoopbackServer.failure(requestCount)) {
            EvidenceArtifactStorageProperties properties = properties(server.endpoint());
            try (
                S3Client client = client(properties);
                S3EvidenceArtifactObjectStorage storage =
                    new S3EvidenceArtifactObjectStorage(client, properties)
            ) {
                assertThatThrownBy(() -> storage.putIfAbsent(
                    EXPECTATION,
                    source(),
                    Instant.now().plusSeconds(30)
                )).isInstanceOfSatisfying(EvidenceArtifactStorageException.class, failure ->
                    assertThat(failure.kind()).isEqualTo(
                        EvidenceArtifactStorageException.FailureKind.OUTCOME_UNCERTAIN
                    )
                );
                assertThat(requestCount).hasValue(1);
            }
        }
    }

    private static S3Client client(EvidenceArtifactStorageProperties properties) {
        return S3Client.builder()
            .credentialsProvider(AnonymousCredentialsProvider.create())
            .endpointOverride(properties.endpoint())
            .region(Region.of(properties.region()))
            .forcePathStyle(true)
            .httpClientBuilder(Apache5HttpClient.builder()
                .connectionTimeout(properties.connectTimeout())
                .socketTimeout(properties.socketTimeout())
                .maxConnections(properties.maximumConnections()))
            .overrideConfiguration(ClientOverrideConfiguration.builder()
                .apiCallAttemptTimeout(properties.apiCallAttemptTimeout())
                .apiCallTimeout(properties.apiCallTimeout())
                .retryStrategy(AwsRetryStrategy.doNotRetry())
                .build())
            .build();
    }

    private static EvidenceArtifactStorageProperties properties(URI endpoint) {
        return new EvidenceArtifactStorageProperties(
            true, endpoint, true, "ap-southeast-1", "evidence-artifacts",
            true, "", KMS_KEY, KMS_KEY, "production-kms",
            1_024, Duration.ofSeconds(1), Duration.ofSeconds(2), Duration.ofSeconds(1),
            Duration.ofSeconds(2), Duration.ofSeconds(5), Duration.ofSeconds(1),
            2, Duration.ofSeconds(30)
        );
    }

    private static ManagedArtifactSource source() {
        return ManagedArtifactSource.forTesting(
            () -> new ByteArrayInputStream(BODY),
            () -> (long) BODY.length,
            () -> { }
        );
    }

    private static String encodedDigest() {
        return Base64.getEncoder().encodeToString(DIGEST.bytes());
    }

    private static byte[] sha256(byte[] body) {
        try {
            return MessageDigest.getInstance("SHA-256").digest(body);
        } catch (NoSuchAlgorithmException exception) {
            throw new IllegalStateException(exception);
        }
    }

    private record CapturedRequest(
        String method,
        String path,
        String ifNoneMatch,
        String checksum,
        String encryption,
        String kmsKey,
        byte[] body
    ) { }

    private static final class LoopbackServer implements AutoCloseable {

        private final HttpServer server;

        private LoopbackServer(HttpServer server) {
            this.server = server;
        }

        static LoopbackServer success(AtomicReference<CapturedRequest> captured) throws IOException {
            HttpServer server = create();
            server.createContext("/", exchange -> respondSuccess(exchange, captured));
            server.start();
            return new LoopbackServer(server);
        }

        static LoopbackServer failure(AtomicInteger requestCount) throws IOException {
            HttpServer server = create();
            server.createContext("/", exchange -> {
                requestCount.incrementAndGet();
                exchange.getRequestBody().readAllBytes();
                byte[] body = "<Error><Code>Unavailable</Code></Error>"
                    .getBytes(StandardCharsets.UTF_8);
                exchange.sendResponseHeaders(503, body.length);
                exchange.getResponseBody().write(body);
                exchange.close();
            });
            server.start();
            return new LoopbackServer(server);
        }

        URI endpoint() {
            return URI.create("http://127.0.0.1:" + server.getAddress().getPort());
        }

        @Override
        public void close() {
            server.stop(0);
        }

        private static HttpServer create() throws IOException {
            return HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        }

        private static void respondSuccess(
            HttpExchange exchange,
            AtomicReference<CapturedRequest> captured
        ) throws IOException {
            captured.set(new CapturedRequest(
                exchange.getRequestMethod(),
                exchange.getRequestURI().getPath(),
                exchange.getRequestHeaders().getFirst("If-None-Match"),
                exchange.getRequestHeaders().getFirst("x-amz-checksum-sha256"),
                exchange.getRequestHeaders().getFirst("x-amz-server-side-encryption"),
                exchange.getRequestHeaders().getFirst("x-amz-server-side-encryption-aws-kms-key-id"),
                exchange.getRequestBody().readAllBytes()
            ));
            exchange.getResponseHeaders().set("x-amz-checksum-sha256", encodedDigest());
            exchange.getResponseHeaders().set("x-amz-server-side-encryption", "aws:kms");
            exchange.getResponseHeaders().set(
                "x-amz-server-side-encryption-aws-kms-key-id",
                KMS_KEY
            );
            exchange.getResponseHeaders().set("x-amz-version-id", "opaque-version");
            exchange.sendResponseHeaders(200, -1);
            exchange.close();
        }
    }
}
