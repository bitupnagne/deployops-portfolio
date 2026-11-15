package com.portfolio.deployops.service;

import static org.assertj.core.api.Assertions.*;
import com.sun.net.httpserver.HttpServer;
import java.io.*;
import java.net.*;
import java.net.http.*;
import java.time.Duration;
import java.util.concurrent.*;
import java.util.concurrent.atomic.*;
import org.junit.jupiter.api.*;

@Timeout(10)
class NexusTransferDeadlineTest {
    private HttpServer server;
    private ExecutorService httpThreads;
    private final CountDownLatch releaseServer = new CountDownLatch(1);
    private final HttpClient client = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(2)).build();

    @BeforeEach void start() throws Exception {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        httpThreads = Executors.newCachedThreadPool(r -> { var t = new Thread(r); t.setDaemon(true); return t; });
        server.setExecutor(httpThreads);
        server.start();
    }
    @AfterEach void stop() {
        releaseServer.countDown();
        server.stop(0);
        httpThreads.shutdownNow();
    }
    private HttpRequest.Builder request(String path) {
        return HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + server.getAddress().getPort() + path));
    }

    @Test void stalledResponseBodyIsClosedAndSinglePublisherCanRunTheNextTask() throws Exception {
        server.createContext("/stall", exchange -> {
            exchange.sendResponseHeaders(200, 1024);
            exchange.getResponseBody().write(new byte[]{1, 2, 3});
            exchange.getResponseBody().flush();
            try { releaseServer.await(5, TimeUnit.SECONDS); } catch (InterruptedException ignored) {}
            exchange.close();
        });
        AtomicBoolean closed = new AtomicBoolean();
        ExecutorService worker = Executors.newSingleThreadExecutor();
        try {
            Future<Boolean> first = worker.submit(() -> {
                try (var deadline = new NexusTransferDeadline(Duration.ofMillis(500))) {
                    var response = deadline.await(client.sendAsync(request("/stall").GET().build(),
                        HttpResponse.BodyHandlers.ofInputStream()));
                    InputStream input = new FilterInputStream(response.body()) {
                        @Override public void close() throws IOException { closed.set(true); super.close(); }
                    };
                    deadline.watchBody(input);
                    byte[] bytes = new byte[64];
                    while (input.read(bytes) != -1) { deadline.check(); deadline.progress(); }
                    deadline.check();
                    return false;
                } catch (IOException expected) { return true; }
            });
            Future<String> second = worker.submit(() -> "NEXT_TASK_RAN");
            assertThat(first.get(4, TimeUnit.SECONDS)).isTrue();
            assertThat(closed).isTrue();
            assertThat(second.get(1, TimeUnit.SECONDS)).isEqualTo("NEXT_TASK_RAN");
        } finally { worker.shutdownNow(); }
    }

    @Test void stalledPutCancelsTheHttpFutureWithoutDeletingOrRetryingTheRemoteAsset() throws Exception {
        CountDownLatch accepted = new CountDownLatch(1);
        CountDownLatch disconnected = new CountDownLatch(1);
        AtomicLong received = new AtomicLong();
        server.createContext("/upload", exchange -> {
            accepted.countDown();
            try { releaseServer.await(5, TimeUnit.SECONDS); } catch (InterruptedException ignored) {}
            try {
                byte[] bytes = new byte[8192];
                for (int n; (n = exchange.getRequestBody().read(bytes)) != -1;) received.addAndGet(n);
            } catch (IOException truncatedRequest) {
                // Cancellation closes the socket before the declared upload can finish.
            } finally { exchange.close(); disconnected.countDown(); }
        });
        AtomicLong sent = new AtomicLong();
        AtomicInteger attempts = new AtomicInteger();
        CountDownLatch sourceClosed = new CountDownLatch(1);
        try (var deadline = new NexusTransferDeadline(Duration.ofMillis(500))) {
            var source = HttpRequest.BodyPublishers.ofInputStream(() -> {
                attempts.incrementAndGet();
                InputStream input = new InputStream() {
                    long remaining = 256L * 1024 * 1024;
                    public int read() { return remaining-- > 0 ? 0 : -1; }
                    public int read(byte[] b, int off, int len) {
                        if (remaining <= 0) return -1;
                        int count = (int)Math.min(remaining, len);
                        java.util.Arrays.fill(b, off, off + count, (byte)0);
                        remaining -= count;
                        return count;
                    }
                    @Override public void close() { sourceClosed.countDown(); }
                };
                try { deadline.watchUpload(input); }
                catch (IOException failed) { throw new UncheckedIOException(failed); }
                return input;
            });
            var future = client.sendAsync(request("/upload").PUT(deadline.publisher(source, sent::set)).build(),
                HttpResponse.BodyHandlers.discarding());
            assertThatThrownBy(() -> deadline.await(future)).isInstanceOf(IOException.class);
            assertThat(accepted.await(1, TimeUnit.SECONDS)).isTrue();
            assertThat(sent.get()).isPositive();
            assertHttpCancellation(future);
            assertThat(sourceClosed.await(2, TimeUnit.SECONDS)).isTrue();
            releaseServer.countDown();
            assertThat(disconnected.await(2, TimeUnit.SECONDS)).isTrue();
            assertThat(received.get()).isLessThan(256L * 1024 * 1024);
            assertThat(attempts.get()).isEqualTo(1);
        }
    }

    @Test void continuousProgressMayExceedTheIdlePeriodWithoutATotalTransferLimit() throws Exception {
        server.createContext("/active", exchange -> {
            exchange.sendResponseHeaders(200, 8);
            try {
                for (int i = 0; i < 8; i++) {
                    exchange.getResponseBody().write(i);
                    exchange.getResponseBody().flush();
                    Thread.sleep(120);
                }
            } catch (InterruptedException ignored) { Thread.currentThread().interrupt(); }
            finally { exchange.close(); }
        });
        long started = System.nanoTime();
        try (var deadline = new NexusTransferDeadline(Duration.ofMillis(500))) {
            var response = deadline.await(client.sendAsync(request("/active").GET().build(),
                HttpResponse.BodyHandlers.ofInputStream()));
            int received = 0;
            while (response.body().read() != -1) { deadline.check(); deadline.progress(); received++; }
            deadline.check();
            assertThat(received).isEqualTo(8);
            assertThat(Duration.ofNanos(System.nanoTime() - started)).isGreaterThan(Duration.ofMillis(500));
        }
    }

    @Test void callerInterruptionCancelsAPutBeforeTheIdleDeadline() throws Exception {
        CountDownLatch accepted = new CountDownLatch(1);
        server.createContext("/interrupt", exchange -> {
            accepted.countDown();
            try { releaseServer.await(5, TimeUnit.SECONDS); } catch (InterruptedException ignored) {}
            exchange.close();
        });
        AtomicReference<CompletableFuture<HttpResponse<Void>>> pending = new AtomicReference<>();
        CountDownLatch finished = new CountDownLatch(1);
        Thread caller = new Thread(() -> {
            try (var deadline = new NexusTransferDeadline(Duration.ofSeconds(30))) {
                var future = client.sendAsync(request("/interrupt").PUT(HttpRequest.BodyPublishers.ofString("test")).build(),
                    HttpResponse.BodyHandlers.discarding());
                pending.set(future);
                deadline.await(future);
            } catch (Exception expected) {
                // Completion is asserted below; an interruption must release the HTTP exchange.
            } finally { finished.countDown(); }
        });
        caller.start();
        try {
            assertThat(accepted.await(2, TimeUnit.SECONDS)).isTrue();
            caller.interrupt();
            assertThat(finished.await(2, TimeUnit.SECONDS)).isTrue();
            assertHttpCancellation(pending.get());
        } finally { caller.interrupt(); caller.join(2000); }
    }

    private static void assertHttpCancellation(CompletableFuture<?> pending) {
        // HttpClient may complete its returned dependent future exceptionally with a
        // CompletionException wrapping CancellationException, rather than marking it cancelled.
        Throwable error = catchThrowable(pending::join);
        assertThat(error).isNotNull();
        while (error != null && !(error instanceof CancellationException)) error = error.getCause();
        assertThat(error).isInstanceOf(CancellationException.class);
    }
}
