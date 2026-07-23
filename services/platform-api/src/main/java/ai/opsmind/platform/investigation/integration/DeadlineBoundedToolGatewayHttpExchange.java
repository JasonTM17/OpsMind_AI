package ai.opsmind.platform.investigation.integration;

import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.net.http.HttpTimeoutException;
import java.time.Duration;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicReference;

import ai.opsmind.platform.common.api.PlatformProblemException;
import ai.opsmind.platform.common.http.BoundedResponseBodySubscriber;
import ai.opsmind.platform.common.http.ResponseBodyTooLargeException;

import org.springframework.http.HttpStatus;

/** Executes one bounded exchange. The caller owns replay decisions after ambiguous failure. */
final class DeadlineBoundedToolGatewayHttpExchange implements ToolGatewayHttpExchange {

    private final HttpClient httpClient;
    private final int maximumResponseBodyBytes;

    DeadlineBoundedToolGatewayHttpExchange(HttpClient httpClient, int maximumResponseBodyBytes) {
        this.httpClient = httpClient;
        this.maximumResponseBodyBytes = maximumResponseBodyBytes;
    }

    @Override
    public HttpResponse<byte[]> send(HttpRequest request, Duration timeout) {
        AtomicReference<BoundedResponseBodySubscriber> responseBody = new AtomicReference<>();
        var exchange = httpClient.sendAsync(request, ignored -> {
            var subscriber = new BoundedResponseBodySubscriber(maximumResponseBodyBytes);
            responseBody.set(subscriber);
            return subscriber;
        });
        try {
            return exchange.get(timeout.toNanos(), TimeUnit.NANOSECONDS);
        }
        catch (TimeoutException exception) {
            abort(exchange, responseBody.get());
            throw timeout(exception);
        }
        catch (InterruptedException exception) {
            abort(exchange, responseBody.get());
            Thread.currentThread().interrupt();
            throw unavailable(exception);
        }
        catch (ExecutionException exception) {
            Throwable cause = rootCause(exception);
            if (cause instanceof ResponseBodyTooLargeException) throw invalidResponse(cause);
            if (cause instanceof HttpTimeoutException) throw timeout(cause);
            throw unavailable(cause);
        }
    }

    private void abort(
        java.util.concurrent.CompletableFuture<?> exchange,
        BoundedResponseBodySubscriber subscriber
    ) {
        if (subscriber == null) exchange.cancel(true);
        else subscriber.abort();
    }

    private PlatformProblemException unavailable(Throwable cause) {
        return problem(HttpStatus.SERVICE_UNAVAILABLE, "dependency.tool-gateway-unavailable",
            "The Tool Gateway is temporarily unavailable.", cause);
    }

    private PlatformProblemException timeout(Throwable cause) {
        return problem(HttpStatus.GATEWAY_TIMEOUT, "dependency.tool-gateway-timeout",
            "The Tool Gateway did not respond before the deadline.", cause);
    }

    private PlatformProblemException invalidResponse(Throwable cause) {
        return problem(HttpStatus.BAD_GATEWAY, "dependency.tool-gateway-invalid-response",
            "The Tool Gateway returned an invalid response.", cause);
    }

    private PlatformProblemException problem(
        HttpStatus status,
        String code,
        String message,
        Throwable cause
    ) {
        return new PlatformProblemException(status, code, message, cause);
    }

    private Throwable rootCause(Throwable throwable) {
        Throwable cause = throwable;
        while (cause.getCause() != null && cause.getCause() != cause) cause = cause.getCause();
        return cause;
    }
}
