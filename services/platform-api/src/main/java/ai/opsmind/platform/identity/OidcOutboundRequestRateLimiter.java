package ai.opsmind.platform.identity;

import java.io.IOException;
import java.net.URI;
import java.time.Duration;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.LongSupplier;

import org.springframework.http.HttpRequest;
import org.springframework.http.client.ClientHttpRequestExecution;
import org.springframework.http.client.ClientHttpRequestInterceptor;
import org.springframework.http.client.ClientHttpResponse;

final class OidcOutboundRequestRateLimiter implements ClientHttpRequestInterceptor {

    private static final long NEVER_REQUESTED = Long.MIN_VALUE;

    private final long minimumIntervalNanos;
    private final LongSupplier nanoTime;
    private final ConcurrentHashMap<URI, AtomicLong> lastRequests = new ConcurrentHashMap<>();

    OidcOutboundRequestRateLimiter(Duration minimumInterval) {
        this(minimumInterval, System::nanoTime);
    }

    OidcOutboundRequestRateLimiter(Duration minimumInterval, LongSupplier nanoTime) {
        if (minimumInterval == null || nanoTime == null
            || minimumInterval.isNegative() || minimumInterval.isZero()) {
            throw new IllegalArgumentException("OIDC outbound request interval and clock are required.");
        }
        this.minimumIntervalNanos = minimumInterval.toNanos();
        this.nanoTime = nanoTime;
    }

    @Override
    public ClientHttpResponse intercept(
        HttpRequest request,
        byte[] body,
        ClientHttpRequestExecution execution
    ) throws IOException {
        URI target = request.getURI();
        AtomicLong lastRequest = lastRequests.computeIfAbsent(
            target,
            ignored -> new AtomicLong(NEVER_REQUESTED)
        );
        long now = nanoTime.getAsLong();
        while (true) {
            long previous = lastRequest.get();
            if (previous != NEVER_REQUESTED && now - previous < minimumIntervalNanos) {
                throw new IOException("OIDC outbound metadata refresh is temporarily rate limited.");
            }
            if (lastRequest.compareAndSet(previous, now)) {
                return execution.execute(request, body);
            }
        }
    }
}
