package com.portfolio.deployops.service;

import com.portfolio.deployops.domain.*;
import java.nio.file.Path;
import java.time.LocalDateTime;
import java.util.List;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class SshAgentTransportTest {
    final SystemSettingService settings = mock(SystemSettingService.class);
    final AgentDispatchService agent = mock(AgentDispatchService.class);
    final SshCommandService transport = new SshCommandService(settings);
    final Server server = Server.builder().id(1L).serverType(ServerType.WAS)
        .controlMode(ServerControlMode.AGENT).agentBindingId("binding").build();
    final List<String> command = List.of("sudo", "-n", "/usr/local/deploy/bin/deploy-runtime.sh", "inspect");

    @Test void agentNeedsNoSshAccountKeyOrIpAndRetainsArgumentBoundaries() {
        transport.configureAgentDispatch(agent);
        when(settings.get(anyString(), anyString())).thenReturn("");
        var now=LocalDateTime.now();
        var expected=new RemoteCommandResult(0,"ok","","agent",now,now,1);
        when(agent.execute(server,command,null,false,240)).thenReturn(expected);
        assertSame(expected,transport.execute(server,command));
        verify(agent).execute(server,command,null,false,240);
        verify(settings,never()).get(eq("deploy.ssh.key-path"),anyString());
    }

    @Test void agentUnavailableNeverFallsBackToSsh() {
        var error=assertThrows(RemoteExecutionException.class,()->transport.execute(server,command));
        assertTrue(error.getMessage().contains("SSH"));
        verifyNoInteractions(settings);
    }

    @Test void sensitiveInputAndStopGracePassThroughWithoutShell() {
        transport.configureAgentDispatch(agent);
        when(settings.get(anyString(),anyString())).thenReturn("");
        byte[] input="private-runtime".getBytes(java.nio.charset.StandardCharsets.UTF_8);
        var args=List.of("/usr/local/deploy/bin/deploy-was-app.sh","--stop-grace-seconds","300");
        transport.executeSensitive(server,args,input);
        verify(agent).execute(server,args,input,true,780);
    }

    @Test void fileCopyIsDispatchedWithoutScpConfiguration() {
        transport.configureAgentDispatch(agent);
        Path local=Path.of("not-opened-by-ssh-transport");
        transport.copyFile(server,local,"/var/tmp/deploy-ops/job/image.tar");
        verify(agent).copyFile(server,local,"/var/tmp/deploy-ops/job/image.tar");
        verifyNoInteractions(settings);
    }

    @Test void legacyServerCannotExecuteOrTransferEvenWithConfiguredSshSettings() {
        Server legacy = Server.builder().controlMode(ServerControlMode.SSH).build();
        transport.configureAgentDispatch(agent);
        assertThrows(RemoteExecutionException.class, () -> transport.execute(legacy, command));
        assertThrows(RemoteExecutionException.class, () -> transport.copyFile(legacy, Path.of("anything"), "/tmp/file"));
        assertThrows(RemoteExecutionException.class, () -> transport.executeSensitive(legacy, command, new byte[0]));
        verifyNoInteractions(agent, settings);
    }

    @Test void newServerDefaultsToAgent() {
        assertEquals(ServerControlMode.AGENT, Server.builder().build().getControlMode());
    }
}
