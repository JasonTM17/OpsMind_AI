package ai.opsmind.platform.identity;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.io.IOException;
import java.net.URI;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CyclicBarrier;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;

import org.junit.jupiter.api.Test;
import org.springframework.http.HttpRequest;
import org.springframework.http.client.ClientHttpRequestExecution;
import org.springframework.http.client.ClientHttpResponse;

class OidcOutboundRequestRateLimiterTest {

    @Test
    void capsConcurrentRefreshesPerTargetAndAllowsLaterRotation() throws Exception {
        int concurrency = 16;
        AtomicLong nanoTime = new AtomicLong(1_000_000_000L);
        var limiter = new OidcOutboundRequestRateLimiter(Duration.ofSeconds(1), nanoTime::get);
        HttpRequest request = request("https://idp.example.test/opsmind/jwks");
        ClientHttpResponse response = mock(ClientHttpResponse.class);
        AtomicInteger outboundCalls = new AtomicInteger();
        ClientHttpRequestExecution execution = (ignoredRequest, ignoredBody) -> {
            outboundCalls.incrementAndGet();
            return response;
        };
        CyclicBarrier start = new CyclicBarrier(concurrency);
        ExecutorService executor = Executors.newFixedThreadPool(concurrency);
        List<Future<Boolean>> attempts = new ArrayList<>();
        try {
            for (int index = 0; index < concurrency; index++) {
                attempts.add(executor.submit(() -> {
                    start.await();
                    try {
                        limiter.intercept(request, new byte[0], execution);
                        return true;
                    }
                    catch (IOException exception) {
                        return false;
                    }
                }));
            }

            int allowed = 0;
            for (Future<Boolean> attempt : attempts) {
                if (attempt.get()) {
                    allowed++;
                }
            }
            assertThat(allowed).isEqualTo(1);
            assertThat(outboundCalls).hasValue(1);

            nanoTime.addAndGet(Duration.ofSeconds(1).toNanos());
            limiter.intercept(request, new byte[0], execution);

            assertThat(outboundCalls).hasValue(2);
        }
        finally {
            executor.shutdownNow();
        }
    }

    @Test
    void tracksDiscoveryAndJwksEndpointsIndependently() throws Exception {
        var limiter = new OidcOutboundRequestRateLimiter(Duration.ofSeconds(1), () -> 1_000_000_000L);
        AtomicInteger outboundCalls = new AtomicInteger();
        ClientHttpResponse response = mock(ClientHttpResponse.class);
        ClientHttpRequestExecution execution = (ignoredRequest, ignoredBody) -> {
            outboundCalls.incrementAndGet();
            return response;
        };

        limiter.intercept(
            request("https://idp.example.test/.well-known/openid-configuration"),
            new byte[0],
            execution
        );
        limiter.intercept(
            request("https://idp.example.test/opsmind/jwks"),
            new byte[0],
            execution
        );

        assertThat(outboundCalls).hasValue(2);
    }

    private HttpRequest request(String uri) {
        HttpRequest request = mock(HttpRequest.class);
        when(request.getURI()).thenReturn(URI.create(uri));
        return request;
    }
}
