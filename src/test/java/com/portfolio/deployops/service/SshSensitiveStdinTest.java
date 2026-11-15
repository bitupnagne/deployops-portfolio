package com.portfolio.deployops.service;
import com.portfolio.deployops.domain.Server;
import com.portfolio.deployops.domain.ServerControlMode;
import java.util.List;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class SshSensitiveStdinTest {
    @Test void structuredInputUsesAgentAndKeepsArgumentBoundaries() {
        var settings = mock(SystemSettingService.class);
        var agent = mock(AgentDispatchService.class);
        var service = new SshCommandService(settings);
        service.configureAgentDispatch(agent);
        var server = Server.builder().build();
        var args = List.of("/usr/local/deploy/bin/deploy-storage.sh", "prepare");
        byte[] payload = "{}".getBytes(java.nio.charset.StandardCharsets.UTF_8);
        service.executeWithInput(server, args, payload);
        verify(agent).execute(server, args, payload, false, 240);
        assertThrows(RemoteExecutionException.class,
            () -> service.executeWithInput(server, args, new byte[65537]));
    }
    @Test void sensitiveInputUsesAgentAndLegacyTransportIsRejected() {
        var settings = mock(SystemSettingService.class);
        var agent = mock(AgentDispatchService.class);
        var service = new SshCommandService(settings);
        service.configureAgentDispatch(agent);
        var args = List.of("/usr/local/deploy/bin/deploy-runtime.sh", "env");
        byte[] payload = "PRIVATE_INPUT".getBytes(java.nio.charset.StandardCharsets.UTF_8);
        var server = Server.builder().build();
        service.executeSensitive(server, args, payload);
        verify(agent).execute(server, args, payload, true, 240);
        clearInvocations(agent, settings);
        var legacy = Server.builder().controlMode(ServerControlMode.SSH).build();
        assertThrows(RemoteExecutionException.class, () -> service.executeSensitive(legacy, args, payload));
        verifyNoInteractions(agent, settings);
    }
}
