package com.portfolio.deployops.service;

import com.portfolio.deployops.domain.Server;
import com.portfolio.deployops.domain.ServerControlMode;
import com.portfolio.deployops.domain.ServerType;
import com.portfolio.deployops.repository.ExecutionGuardRepository;
import com.portfolio.deployops.repository.ServerRepository;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.time.LocalDateTime;
import java.util.Base64;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.FutureTask;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class NexusAgentBrokerConcurrencyTest {
    private final JdbcTemplate jdbc = mock(JdbcTemplate.class);
    private final ServerRepository servers = mock(ServerRepository.class);
    private final AgentDispatchService dispatch = mock(AgentDispatchService.class);
    private final Server server = Server.builder().id(3L).serverType(ServerType.DEPLOY)
        .controlMode(ServerControlMode.AGENT).agentBindingId("binding-3").build();
    private final NexusAgentBrokerTransport transport = new NexusAgentBrokerTransport(jdbc, servers, dispatch,
        mock(AgentQueueService.class), mock(ExecutionGuardRepository.class));

    NexusAgentBrokerConcurrencyTest() {
        when(jdbc.queryForMap(anyString())).thenReturn(Map.of("server_id", 3L, "agent_binding_id", "binding-3"));
        when(servers.findById(3L)).thenReturn(Optional.of(server));
    }

    private RemoteCommandResult response(String text) {
        String output = "{\"ok\":true,\"dataBase64\":\""
            + Base64.getEncoder().encodeToString(text.getBytes(StandardCharsets.UTF_8)) + "\"}";
        var now = LocalDateTime.now();
        return new RemoteCommandResult(0, output, "", "agent", now, now, 1);
    }

    private byte[] ready() throws IOException {
        return transport.exchange("read", "results/ready.json", null);
    }

    private static void awaitBlocked(Thread thread) throws InterruptedException {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5);
        while (thread.getState() != Thread.State.BLOCKED && System.nanoTime() < deadline) Thread.sleep(5);
        assertEquals(Thread.State.BLOCKED, thread.getState(), "Second caller must wait while the first host job is running");
    }

    @Test void overlappingReadinessRequestsShareOneHostJobAndIndependentBytes() throws Exception {
        CountDownLatch entered = new CountDownLatch(1), release = new CountDownLatch(1);
        when(dispatch.execute(eq(server), anyList(), any(), eq(false), eq(30))).thenAnswer(call -> {
            entered.countDown();
            assertTrue(release.await(5, TimeUnit.SECONDS));
            return response("{\"ready\":true}");
        });
        FutureTask<byte[]> first = new FutureTask<>(this::ready), second = new FutureTask<>(this::ready);
        Thread one = new Thread(first), two = new Thread(second);
        one.start();
        try {
            assertTrue(entered.await(5, TimeUnit.SECONDS));
            two.start();
            awaitBlocked(two);
        } finally { release.countDown(); }
        byte[] a = first.get(5, TimeUnit.SECONDS), b = second.get(5, TimeUnit.SECONDS);
        assertArrayEquals(a, b);
        a[0] = 0;
        assertNotEquals(a[0], b[0]);
        verify(dispatch, times(1)).execute(eq(server), anyList(), any(), eq(false), eq(30));
    }

    @Test void differentBrokerOperationsAreSerializedWithoutSharingResults() throws Exception {
        CountDownLatch entered = new CountDownLatch(1), release = new CountDownLatch(1);
        AtomicInteger calls = new AtomicInteger(), active = new AtomicInteger(), maximum = new AtomicInteger();
        when(dispatch.execute(eq(server), anyList(), any(), eq(false), eq(30))).thenAnswer(call -> {
            maximum.accumulateAndGet(active.incrementAndGet(), Math::max);
            try {
                int count = calls.incrementAndGet();
                if (count == 1) { entered.countDown(); assertTrue(release.await(5, TimeUnit.SECONDS)); }
                return response(count == 1 ? "ready" : "false");
            } finally { active.decrementAndGet(); }
        });
        FutureTask<byte[]> first = new FutureTask<>(this::ready);
        FutureTask<byte[]> second = new FutureTask<>(() -> transport.exchange("exists", "results/1.json", null));
        Thread one = new Thread(first), two = new Thread(second);
        one.start();
        try {
            assertTrue(entered.await(5, TimeUnit.SECONDS));
            two.start();
            awaitBlocked(two);
        } finally { release.countDown(); }
        assertEquals("ready", new String(first.get(5, TimeUnit.SECONDS), StandardCharsets.UTF_8));
        assertEquals("false", new String(second.get(5, TimeUnit.SECONDS), StandardCharsets.UTF_8));
        assertEquals(1, maximum.get());
        assertEquals(2, calls.get());
    }

    @Test void laterReadinessRequestAlwaysReadsFreshHostState() throws Exception {
        when(dispatch.execute(eq(server), anyList(), any(), eq(false), eq(30)))
            .thenReturn(response("{\"ready\":true}"), response("{\"ready\":false}"));
        assertEquals("{\"ready\":true}", new String(ready(), StandardCharsets.UTF_8));
        assertEquals("{\"ready\":false}", new String(ready(), StandardCharsets.UTF_8));
        verify(dispatch, times(2)).execute(eq(server), anyList(), any(), eq(false), eq(30));
    }

    @Test void failedHostReadDoesNotReuseAnEarlierSuccess() throws Exception {
        when(dispatch.execute(eq(server), anyList(), any(), eq(false), eq(30)))
            .thenReturn(response("{\"ready\":true}"))
            .thenThrow(new RemoteExecutionException("agent unavailable"));
        ready();
        assertThrows(IOException.class, this::ready);
        verify(dispatch, times(2)).execute(eq(server), anyList(), any(), eq(false), eq(30));
    }

    @Test void changedBindingIsRejectedBeforeAnyCachedReadinessCanBeUsed() throws Exception {
        when(dispatch.execute(eq(server), anyList(), any(), eq(false), eq(30))).thenReturn(response("{\"ready\":true}"));
        ready();
        when(jdbc.queryForMap(anyString())).thenReturn(Map.of("server_id", 3L, "agent_binding_id", "different-binding"));
        assertEquals("NEXUS_AGENT_BINDING_REQUIRED", assertThrows(IOException.class, this::ready).getMessage());
        verify(dispatch, times(1)).execute(eq(server), anyList(), any(), eq(false), eq(30));
    }
}
