package com.portfolio.deployops.service;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.net.http.HttpResponse;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.Flow;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.ScheduledThreadPoolExecutor;
import java.util.concurrent.TimeUnit;

/**
 * Bound memory before copying bytes and separately bound the body-transfer time:
 * a server returning headers must not be able to stall the metadata worker.
 */
final class BoundedArtifactBodyHandler implements HttpResponse.BodyHandler<String> {
    private final int maximumBytes;
    private final Duration bodyTimeout;
    private static final ScheduledThreadPoolExecutor WATCHDOG = watchdog();
    private static ScheduledThreadPoolExecutor watchdog() {
        var executor = new ScheduledThreadPoolExecutor(1, runnable -> {
            Thread thread = new Thread(runnable, "gitlab-artifact-deadline"); thread.setDaemon(true); return thread;
        });
        executor.setRemoveOnCancelPolicy(true);
        return executor;
    }
    BoundedArtifactBodyHandler(int maximumBytes) {
        this(maximumBytes, Duration.ofSeconds(15));
    }
    BoundedArtifactBodyHandler(int maximumBytes, Duration bodyTimeout) {
        if (maximumBytes < 1 || maximumBytes > 256 * 1024) throw new IllegalArgumentException("Invalid artifact limit");
        if (bodyTimeout == null || bodyTimeout.isNegative() || bodyTimeout.isZero()) throw new IllegalArgumentException("Invalid artifact deadline");
        this.maximumBytes = maximumBytes;
        this.bodyTimeout = bodyTimeout;
    }

    @Override public HttpResponse.BodySubscriber<String> apply(HttpResponse.ResponseInfo response) {
        long length = response.headers().firstValueAsLong("Content-Length").orElse(-1);
        return new Subscriber(maximumBytes, length > maximumBytes, bodyTimeout);
    }

    private static final class Subscriber implements HttpResponse.BodySubscriber<String> {
        private final CompletableFuture<String> result = new CompletableFuture<>();
        private final ByteArrayOutputStream bytes;
        private final int limit;
        private final boolean oversizedHeader;
        private Flow.Subscription subscription;
        private ScheduledFuture<?> deadline;
        private final Duration timeout;
        private boolean done;
        Subscriber(int limit, boolean oversizedHeader, Duration timeout) {
            this.limit = limit; this.oversizedHeader = oversizedHeader;
            this.timeout = timeout;
            this.bytes = new ByteArrayOutputStream(Math.min(limit, 8192));
        }
        @Override public CompletionStage<String> getBody() { return result; }
        @Override public synchronized void onSubscribe(Flow.Subscription subscription) {
            this.subscription = subscription;
            if (oversizedHeader) { fail("GitLab artifact exceeds the permitted metadata size."); return; }
            deadline = WATCHDOG.schedule(() -> fail("GitLab artifact body transfer timed out."),
                timeout.toMillis(), TimeUnit.MILLISECONDS);
            subscription.request(1);
        }
        @Override public synchronized void onNext(List<ByteBuffer> buffers) {
            if (done) return;
            for (ByteBuffer buffer : buffers) {
                if (buffer.remaining() > limit - bytes.size()) { fail("GitLab artifact exceeds the permitted metadata size."); return; }
                while (buffer.hasRemaining()) bytes.write(buffer.get());
            }
            subscription.request(1);
        }
        private synchronized void fail(String message) {
            if (done) return;
            done = true;
            if (deadline != null) deadline.cancel(false);
            // Publish our bounded-transfer failure before cancellation can cause the
            // HTTP transport to race it with a generic chunk/connection exception.
            try {
                result.completeExceptionally(new IOException(message));
            } finally {
                subscription.cancel();
            }
        }
        @Override public synchronized void onError(Throwable error) {
            if (done) return;
            done = true;
            if (deadline != null) deadline.cancel(false);
            result.completeExceptionally(error);
        }
        @Override public synchronized void onComplete() {
            if (done) return;
            done = true;
            if (deadline != null) deadline.cancel(false);
            result.complete(bytes.toString(StandardCharsets.UTF_8));
        }
    }
}
