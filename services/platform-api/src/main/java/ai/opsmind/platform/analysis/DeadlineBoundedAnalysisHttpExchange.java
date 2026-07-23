package ai.opsmind.platform.analysis;

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

/** Executes one bounded HTTP exchange and enforces the deadline through body completion. */
final class DeadlineBoundedAnalysisHttpExchange {

    private final AnalysisRuntimeClientProperties properties;
    private final HttpClient httpClient;

    DeadlineBoundedAnalysisHttpExchange(
        AnalysisRuntimeClientProperties properties,
        HttpClient httpClient
    ) {
        this.properties = properties;
        this.httpClient = httpClient;
    }

    HttpResponse<byte[]> send(HttpRequest request, Duration timeout) {
        AtomicReference<BoundedResponseBodySubscriber> responseBody = new AtomicReference<>();
        var exchange = httpClient.sendAsync(
            request,
            ignored -> {
                var subscriber = new BoundedResponseBodySubscriber(
                    properties.maxResponseBodyBytes()
                );
                responseBody.set(subscriber);
                return subscriber;
            }
        );
        try {
            return exchange.get(timeout.toNanos(), TimeUnit.NANOSECONDS);
        }
        catch (TimeoutException exception) {
            BoundedResponseBodySubscriber subscriber = responseBody.get();
            if (subscriber == null) {
                exchange.cancel(true);
            }
            else {
                subscriber.abort();
            }
            throw timeout(exception);
        }
        catch (InterruptedException exception) {
            BoundedResponseBodySubscriber subscriber = responseBody.get();
            if (subscriber != null) {
                subscriber.abort();
            }
            exchange.cancel(true);
            Thread.currentThread().interrupt();
            throw unavailable(exception);
        }
        catch (ExecutionException exception) {
            Throwable cause = rootCause(exception);
            if (cause instanceof ResponseBodyTooLargeException) {
                throw invalidResponse(cause);
            }
            if (cause instanceof HttpTimeoutException) {
                throw timeout(cause);
            }
            throw unavailable(cause);
        }
    }

    private PlatformProblemException unavailable(Throwable cause) {
        return new PlatformProblemException(
            HttpStatus.SERVICE_UNAVAILABLE,
            "dependency.ai-runtime-unavailable",
            "The analysis runtime is temporarily unavailable.",
            cause
        );
    }

    private PlatformProblemException timeout(Throwable cause) {
        return new PlatformProblemException(
            HttpStatus.GATEWAY_TIMEOUT,
            "dependency.ai-runtime-timeout",
            "The analysis runtime did not respond before the deadline.",
            cause
        );
    }

    private PlatformProblemException invalidResponse(Throwable cause) {
        return new PlatformProblemException(
            HttpStatus.BAD_GATEWAY,
            "dependency.ai-runtime-invalid-response",
            "The analysis runtime returned an invalid response.",
            cause
        );
    }

    private Throwable rootCause(Throwable throwable) {
        Throwable cause = throwable;
        while (cause.getCause() != null && cause.getCause() != cause) {
            cause = cause.getCause();
        }
        return cause;
    }
}
