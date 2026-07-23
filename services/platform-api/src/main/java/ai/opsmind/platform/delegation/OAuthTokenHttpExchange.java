package ai.opsmind.platform.delegation;

import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.net.http.HttpTimeoutException;
import java.time.Duration;
import java.util.Locale;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicReference;

import ai.opsmind.platform.common.http.BoundedResponseBodySubscriber;
import ai.opsmind.platform.common.http.ResponseBodyTooLargeException;

final class OAuthTokenHttpExchange {

    private static final String FAILURE = "Workload token endpoint is unavailable.";

    private final HttpClient httpClient;
    private final int maximumResponseBytes;

    OAuthTokenHttpExchange(HttpClient httpClient, int maximumResponseBytes) {
        this.httpClient = httpClient;
        this.maximumResponseBytes = maximumResponseBytes;
    }

    byte[] send(HttpRequest request, Duration timeout) {
        AtomicReference<BoundedResponseBodySubscriber> responseBody = new AtomicReference<>();
        var exchange = httpClient.sendAsync(request, ignored -> {
            var subscriber = new BoundedResponseBodySubscriber(maximumResponseBytes);
            responseBody.set(subscriber);
            return subscriber;
        });
        try {
            HttpResponse<byte[]> response = exchange.get(timeout.toNanos(), TimeUnit.NANOSECONDS);
            if (response.statusCode() != 200 || !hasJsonContentType(response)) {
                throw new WorkloadTokenUnavailableException(FAILURE);
            }
            return response.body();
        }
        catch (TimeoutException exception) {
            abort(exchange, responseBody.get());
            throw new WorkloadTokenUnavailableException(FAILURE, exception);
        }
        catch (InterruptedException exception) {
            abort(exchange, responseBody.get());
            Thread.currentThread().interrupt();
            throw new WorkloadTokenUnavailableException(FAILURE, exception);
        }
        catch (ExecutionException exception) {
            Throwable cause = rootCause(exception);
            if (cause instanceof ResponseBodyTooLargeException
                || cause instanceof HttpTimeoutException) {
                throw new WorkloadTokenUnavailableException(FAILURE, cause);
            }
            throw new WorkloadTokenUnavailableException(FAILURE, cause);
        }
    }

    private void abort(
        java.util.concurrent.CompletableFuture<?> exchange,
        BoundedResponseBodySubscriber subscriber
    ) {
        if (subscriber == null) exchange.cancel(true);
        else subscriber.abort();
    }

    private boolean hasJsonContentType(HttpResponse<?> response) {
        return response.headers().firstValue("Content-Type")
            .map(value -> value.split(";", 2)[0].trim().toLowerCase(Locale.ROOT))
            .filter("application/json"::equals)
            .isPresent();
    }

    private Throwable rootCause(Throwable throwable) {
        Throwable cause = throwable;
        while (cause.getCause() != null && cause.getCause() != cause) cause = cause.getCause();
        return cause;
    }
}
