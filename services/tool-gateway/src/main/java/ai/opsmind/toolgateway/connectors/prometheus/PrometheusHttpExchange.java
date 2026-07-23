package ai.opsmind.toolgateway.connectors.prometheus;

import java.net.URI;
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

import ai.opsmind.toolgateway.common.http.BoundedResponseBodySubscriber;
import ai.opsmind.toolgateway.common.http.ResponseBodyTooLargeException;
import ai.opsmind.toolgateway.domain.DenialCode;
import ai.opsmind.toolgateway.domain.ToolDeniedException;

final class PrometheusHttpExchange implements PrometheusExchange {

    private final HttpClient httpClient;
    private final int maximumResponseBytes;

    PrometheusHttpExchange(HttpClient httpClient, int maximumResponseBytes) {
        this.httpClient = httpClient;
        this.maximumResponseBytes = maximumResponseBytes;
    }

    @Override
    public byte[] getJson(URI endpoint, Duration timeout) {
        HttpResponse<byte[]> response = send(endpoint, timeout, maximumResponseBytes);
        if (response.statusCode() != 200
            || !json(response.headers().firstValue("Content-Type").orElse(""))
            || encoded(response.headers().firstValue("Content-Encoding").orElse(""))) {
            throw failed(null, "Prometheus returned an invalid HTTP response.");
        }
        return response.body();
    }

    @Override
    public boolean ready(URI endpoint, Duration timeout) {
        try {
            return send(endpoint, timeout, 256).statusCode() == 200;
        }
        catch (RuntimeException exception) {
            return false;
        }
    }

    private HttpResponse<byte[]> send(URI endpoint, Duration timeout, int maximumBytes) {
        HttpRequest request = HttpRequest.newBuilder(endpoint)
            .timeout(timeout)
            .header("Accept", "application/json")
            .header("User-Agent", "opsmind-tool-gateway/prometheus-read-only")
            .GET()
            .build();
        AtomicReference<BoundedResponseBodySubscriber> body = new AtomicReference<>();
        var exchange = httpClient.sendAsync(request, ignored -> {
            var subscriber = new BoundedResponseBodySubscriber(maximumBytes);
            body.set(subscriber);
            return subscriber;
        });
        try {
            return exchange.get(timeout.toNanos(), TimeUnit.NANOSECONDS);
        }
        catch (TimeoutException exception) {
            abort(exchange, body.get());
            throw denied(DenialCode.CONNECTOR_TIMEOUT, "Prometheus request timed out.", exception);
        }
        catch (InterruptedException exception) {
            abort(exchange, body.get());
            Thread.currentThread().interrupt();
            throw denied(
                DenialCode.CONNECTOR_CANCELLED,
                "Prometheus request was cancelled.",
                exception
            );
        }
        catch (ExecutionException exception) {
            Throwable cause = rootCause(exception);
            if (cause instanceof ResponseBodyTooLargeException) {
                throw denied(
                    DenialCode.RESULT_OVERSIZE,
                    "Prometheus response exceeded the byte ceiling.",
                    cause
                );
            }
            if (cause instanceof HttpTimeoutException) {
                throw denied(
                    DenialCode.CONNECTOR_TIMEOUT,
                    "Prometheus request timed out.",
                    cause
                );
            }
            throw failed(cause, "Prometheus is unavailable.");
        }
    }

    private void abort(
        java.util.concurrent.CompletableFuture<?> exchange,
        BoundedResponseBodySubscriber subscriber
    ) {
        exchange.cancel(true);
        if (subscriber != null) subscriber.abort();
    }

    private boolean json(String contentType) {
        return contentType.split(";", 2)[0].trim().equalsIgnoreCase("application/json");
    }

    private boolean encoded(String contentEncoding) {
        String value = contentEncoding.trim().toLowerCase(Locale.ROOT);
        return !value.isEmpty() && !"identity".equals(value);
    }

    private Throwable rootCause(Throwable throwable) {
        Throwable cause = throwable;
        while (cause.getCause() != null && cause.getCause() != cause) cause = cause.getCause();
        return cause;
    }

    private ToolDeniedException failed(Throwable cause, String message) {
        return denied(DenialCode.CONNECTOR_FAILED, message, cause);
    }

    private ToolDeniedException denied(DenialCode code, String message, Throwable cause) {
        return new ToolDeniedException(code, message, cause);
    }
}
