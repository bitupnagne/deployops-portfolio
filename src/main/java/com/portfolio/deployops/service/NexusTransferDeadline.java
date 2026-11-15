package com.portfolio.deployops.service;

import java.io.Closeable;
import java.io.IOException;
import java.io.InterruptedIOException;
import java.net.http.HttpRequest;
import java.nio.ByteBuffer;
import java.time.Duration;
import java.util.concurrent.*;
import java.util.concurrent.atomic.*;
import java.util.function.LongConsumer;

/** An inactivity deadline, not a maximum artifact size or total transfer duration. */
final class NexusTransferDeadline implements AutoCloseable {
    private static final ScheduledExecutorService WATCHDOG = Executors.newSingleThreadScheduledExecutor(r -> {
        Thread thread = new Thread(r, "nexus-transfer-idle-watchdog");
        thread.setDaemon(true);
        return thread;
    });
    private final long idleNanos;
    private final AtomicLong activity = new AtomicLong(System.nanoTime());
    private final AtomicReference<IOException> failure = new AtomicReference<>();
    private final AtomicReference<CompletableFuture<?>> pending = new AtomicReference<>();
    private final AtomicReference<Closeable> body = new AtomicReference<>();
    private final AtomicReference<Closeable> uploadBody = new AtomicReference<>();
    private final AtomicReference<Flow.Subscription> uploadSubscription = new AtomicReference<>();
    private final AtomicBoolean closed = new AtomicBoolean();
    private final Thread owner = Thread.currentThread();
    private final ScheduledFuture<?> watch;

    NexusTransferDeadline(Duration idle) {
        if (idle == null || idle.isZero() || idle.isNegative()) throw new IllegalArgumentException("idle timeout");
        idleNanos = idle.toNanos();
        long periodMillis = Math.max(10, Math.min(1000, idle.toMillis() / 4));
        watch = WATCHDOG.scheduleAtFixedRate(() -> {
            if (closed.get()) return;
            if (owner.isInterrupted()) {
                abort(new InterruptedIOException("Nexus 파일 전송이 중단되었습니다."));
            } else if (System.nanoTime() - activity.get() >= idleNanos) {
                abort(new IOException("Nexus 파일 전송의 네트워크 진행이 없어 중단했습니다. 원격 등록 결과를 확인하세요."));
            }
        }, periodMillis, periodMillis, TimeUnit.MILLISECONDS);
    }

    void progress() { activity.set(System.nanoTime()); }

    void check() throws IOException {
        IOException error = failure.get();
        if (error != null) throw error;
        if (owner.isInterrupted()) throw new InterruptedIOException("Nexus 파일 전송이 중단되었습니다.");
    }

    <T> T await(CompletableFuture<T> future) throws Exception {
        pending.set(future);
        if (failure.get() != null || closed.get()) future.cancel(true);
        try {
            T result = future.get();
            if (result instanceof java.net.http.HttpResponse<?> response && response.body() instanceof Closeable stream)
                watchBody(stream);
            check();
            return result;
        } catch (CancellationException cancelled) {
            check();
            throw new IOException("Nexus 파일 전송이 취소되었습니다.", cancelled);
        } catch (ExecutionException failed) {
            check();
            if (failed.getCause() instanceof Exception cause) throw cause;
            throw new IOException("Nexus 파일 전송에 실패했습니다.", failed.getCause());
        } catch (InterruptedException interrupted) {
            future.cancel(true);
            Thread.currentThread().interrupt();
            throw interrupted;
        }
    }

    void watchBody(Closeable stream) throws IOException {
        body.set(stream);
        if (failure.get() != null || closed.get()) closeBody();
        check();
    }

    void watchUpload(Closeable stream) throws IOException {
        uploadBody.set(stream);
        if (failure.get() != null || closed.get()) closeUpload();
        check();
    }

    HttpRequest.BodyPublisher publisher(HttpRequest.BodyPublisher source, LongConsumer callback) {
        return new HttpRequest.BodyPublisher() {
            public long contentLength() { return source.contentLength(); }
            public void subscribe(Flow.Subscriber<? super ByteBuffer> downstream) {
                source.subscribe(new Flow.Subscriber<ByteBuffer>() {
                    private Flow.Subscription upstream;
                    private long sent;
                    public void onSubscribe(Flow.Subscription subscription) {
                        upstream = subscription;
                        uploadSubscription.set(subscription);
                        downstream.onSubscribe(subscription);
                        if (failure.get() != null || closed.get()) subscription.cancel();
                    }
                    public void onNext(ByteBuffer bytes) {
                        try {
                            check();
                            progress();
                            sent += bytes.remaining();
                            callback.accept(sent);
                            downstream.onNext(bytes);
                        } catch (Exception error) {
                            upstream.cancel();
                            downstream.onError(error);
                        }
                    }
                    public void onError(Throwable error) { downstream.onError(error); }
                    public void onComplete() { downstream.onComplete(); }
                });
            }
        };
    }

    private void abort(IOException error) {
        if (!failure.compareAndSet(null, error)) return;
        CompletableFuture<?> future = pending.get();
        if (future != null) future.cancel(true);
        cancelUpload();
        closeUpload();
        closeBody();
    }

    private void cancelUpload() {
        Flow.Subscription subscription = uploadSubscription.getAndSet(null);
        if (subscription != null) subscription.cancel();
    }

    private void closeBody() {
        Closeable stream = body.getAndSet(null);
        if (stream != null) try { stream.close(); } catch (IOException ignored) { /* already aborting */ }
    }

    private void closeUpload() {
        Closeable stream = uploadBody.getAndSet(null);
        if (stream != null) try { stream.close(); } catch (IOException ignored) { /* already aborting */ }
    }

    @Override public void close() {
        if (!closed.compareAndSet(false, true)) return;
        watch.cancel(false);
        CompletableFuture<?> future = pending.getAndSet(null);
        if (future != null && !future.isDone()) future.cancel(true);
        cancelUpload();
        closeUpload();
        closeBody();
    }
}
