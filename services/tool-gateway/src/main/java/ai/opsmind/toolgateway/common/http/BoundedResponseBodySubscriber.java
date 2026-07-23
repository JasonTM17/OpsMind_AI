package ai.opsmind.toolgateway.common.http;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.net.http.HttpResponse;
import java.nio.ByteBuffer;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.Flow;

/** Collects an HTTP response body while cancelling at the byte ceiling. */
public final class BoundedResponseBodySubscriber
    implements HttpResponse.BodySubscriber<byte[]> {

    private final int maximumBytes;
    private final ByteArrayOutputStream buffer;
    private final CompletableFuture<byte[]> result = new CompletableFuture<>();
    private Flow.Subscription subscription;
    private boolean aborted;

    public BoundedResponseBodySubscriber(int maximumBytes) {
        if (maximumBytes < 1) {
            throw new IllegalArgumentException("Response body limit must be positive.");
        }
        this.maximumBytes = maximumBytes;
        this.buffer = new ByteArrayOutputStream(Math.min(maximumBytes, 8_192));
    }

    @Override
    public CompletionStage<byte[]> getBody() {
        return result;
    }

    @Override
    public synchronized void onSubscribe(Flow.Subscription candidate) {
        if (subscription != null || aborted) {
            candidate.cancel();
            return;
        }
        subscription = candidate;
        candidate.request(Long.MAX_VALUE);
    }

    @Override
    public synchronized void onNext(List<ByteBuffer> items) {
        if (result.isDone()) return;
        for (ByteBuffer item : items) {
            int remaining = item.remaining();
            if (remaining > maximumBytes - buffer.size()) {
                subscription.cancel();
                result.completeExceptionally(new ResponseBodyTooLargeException());
                return;
            }
            byte[] bytes = new byte[remaining];
            item.get(bytes);
            buffer.writeBytes(bytes);
        }
    }

    @Override
    public synchronized void onError(Throwable throwable) {
        result.completeExceptionally(throwable);
    }

    @Override
    public synchronized void onComplete() {
        result.complete(buffer.toByteArray());
    }

    public synchronized void abort() {
        aborted = true;
        if (subscription != null) subscription.cancel();
        result.completeExceptionally(new IOException("HTTP response collection was cancelled."));
    }
}
