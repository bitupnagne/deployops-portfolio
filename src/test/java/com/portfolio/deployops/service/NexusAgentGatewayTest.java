package com.portfolio.deployops.service;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.Map;
import org.junit.jupiter.api.Test;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

class NexusAgentGatewayTest {
    private final NexusAgentBrokerTransport remote=mock(NexusAgentBrokerTransport.class);
    private final NexusBackupJobGateway gateway=new NexusBackupJobGateway("absent-agent-test-root");

    NexusAgentGatewayTest() {
        when(remote.configured()).thenReturn(true);
        gateway.configureAgentTransport(remote);
    }

    @Test void backupUsesAgentWithoutLocalQueueDirectory() throws Exception {
        when(remote.exchange("read","results/ready.json",null)).thenReturn(
            "{\"schema\":1,\"ready\":true,\"externalWritersManaged\":true}".getBytes(StandardCharsets.UTF_8));
        gateway.submit(17);
        verify(remote).exchange("publish","inbox/17.json",Map.of("schema",1,"requestId",17L,"action","BACKUP"));
    }

    @Test void absentResultDoesNotReadOrFallbackToLocal() throws Exception {
        when(remote.exchange("exists","results/17.json",null)).thenReturn("false".getBytes(StandardCharsets.UTF_8));
        assertThat(gateway.result(17)).isNull();
        verify(remote,never()).exchange(eq("read"),anyString(),any());
    }

    @Test void uncertainResultIsNotTreatedAsMissing() throws Exception {
        when(remote.exchange("exists","results/17.json",null)).thenReturn("unknown".getBytes(StandardCharsets.UTF_8));
        assertThatThrownBy(()->gateway.result(17)).isInstanceOf(IOException.class).hasMessage("BROKER_FILE_STATE_UNKNOWN");
    }

    @Test void agentOutageDoesNotFallbackToLocalFiles() throws Exception {
        when(remote.exchange("exists","results/17.json",null)).thenThrow(new IOException("agent unavailable"));
        assertThatThrownBy(()->gateway.result(17)).isInstanceOf(IOException.class).hasMessage("agent unavailable");
        assertThat(gateway.ready()).isFalse();
    }

    @Test void recoveryCheckRetainsImmutableTokenAndPayload() throws Exception {
        var payload=Map.<String,Object>of("schema",1,"action","CHECK_RECOVERY","requestId",17,"checkId",29);
        gateway.publishExactCheck("17-29.check.json",payload);
        verify(remote).exchange("publish-exact","inbox/17-29.check.json",payload);
    }

    @Test void resultTraversalNeverReachesAgent() throws Exception {
        assertThatThrownBy(()->gateway.exists(Path.of("elsewhere/results/17.json"))).isInstanceOf(IOException.class);
        verify(remote,never()).exchange(anyString(),anyString(),any());
    }
}
