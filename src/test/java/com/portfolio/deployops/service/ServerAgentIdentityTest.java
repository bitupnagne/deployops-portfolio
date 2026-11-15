package com.portfolio.deployops.service;

import com.portfolio.deployops.domain.*;
import com.portfolio.deployops.dto.ServerForm;
import com.portfolio.deployops.repository.*;
import java.util.*;
import org.junit.jupiter.api.Test;
import org.springframework.web.server.ResponseStatusException;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class ServerAgentIdentityTest {
    final ServerRepository servers=mock(ServerRepository.class);
    final AgentQueueService agents=mock(AgentQueueService.class);
    final ServerService service=new ServerService(servers,mock(RemoteCommandLogRepository.class),mock(ManualServerExecutionRepository.class));
    final Server old=Server.builder().id(8L).name("was8").ipAddress("192.0.2.8")
        .serverType(ServerType.WAS).environment(ServerEnvironment.TEST).controlMode(ServerControlMode.AGENT).build();
    final ServerGroupRepository groups=mock(ServerGroupRepository.class);
    ServerForm replacement() {
        service.configureAgentQueue(agents);
        when(servers.findByIdForMutation(8L)).thenReturn(Optional.of(old));
        var form=new ServerForm();form.setName("was8");form.setIpAddress("192.0.2.9");
        form.setServerType(ServerType.WAS);form.setEnvironment(ServerEnvironment.TEST);form.setControlMode(ServerControlMode.AGENT);
        return form;
    }
    @Test void activeAgentCannotBeRepointedToAnotherHost() {
        var form=replacement();
        when(agents.status(8L)).thenReturn(Map.of("registrationState","ACTIVE"));
        assertEquals(409,assertThrows(ResponseStatusException.class,()->service.update(8L,form,groups)).getStatusCode().value());
        assertEquals("192.0.2.8",old.getIpAddress());verify(servers,never()).save(any());
    }
    @Test void revokedButUncertainTaskCannotEscapeBySwitchingToSsh() {
        var form=replacement();form.setControlMode(ServerControlMode.SSH);
        when(agents.status(8L)).thenReturn(Map.of("registrationState","REVOKED","busyTask",Map.of("state","UNKNOWN")));
        assertEquals(400,assertThrows(ResponseStatusException.class,()->service.update(8L,form,groups)).getStatusCode().value());
        assertTrue(old.isAgentControlled());verify(servers,never()).save(any());
    }
    @Test void registrationHistoryPreventsAnUnclearForeignKeyFailureOnDelete() {
        replacement();when(agents.status(8L)).thenReturn(Map.of("registrationState","REVOKED"));
        var error=assertThrows(ResponseStatusException.class,()->service.delete(8L));
        assertEquals(409,error.getStatusCode().value());verify(servers,never()).delete(any());
    }
    @Test void agentOnlyFormPreservesHistoricalAccountWhenEditingName() {
        var form=replacement();form.setIpAddress(old.getIpAddress());form.setName("renamed-was");
        old.setDeployAccount("historical-deploy");old.setSshPort(2022);
        when(servers.save(any())).thenAnswer(call -> call.getArgument(0));
        Server updated=service.update(8L,form,groups);
        assertEquals("historical-deploy",updated.getDeployAccount());
        assertEquals(2022,updated.getSshPort());
        assertEquals("renamed-was",updated.getName());
        verifyNoInteractions(agents);
    }

}
