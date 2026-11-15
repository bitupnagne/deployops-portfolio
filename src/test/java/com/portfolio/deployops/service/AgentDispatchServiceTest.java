package com.portfolio.deployops.service;

import com.portfolio.deployops.domain.Server;
import java.time.LocalDateTime;
import java.util.*;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.json.JsonMapper;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class AgentDispatchServiceTest {
    final AgentQueueService queue=mock(AgentQueueService.class);
    final AgentDispatchService dispatch=new AgentDispatchService(queue,JsonMapper.builder().build());
    final Server server=Server.builder().id(7L).build();
    AgentQueueService.Task task(String state,boolean sensitive) {
        return new AgentQueueService.Task("job7",7L,1L,"EXEC",state,"payload",sensitive,30,LocalDateTime.now(),LocalDateTime.now().plusSeconds(30),LocalDateTime.now(),LocalDateTime.now(),state.equals("FAILED")?4:0,"stdout-secret","stderr-secret",20L,null,null,null,null);
    }
    @Test void uncertainRemoteOutcomeIsNotReturnedAsAFailedExitCode() {
        when(queue.enqueue(eq(server),eq("EXEC"),anyMap(),eq(false),eq(30),isNull(),isNull(),isNull())).thenReturn(task("QUEUED",false));
        when(queue.task("job7",false)).thenReturn(task("UNKNOWN",false));
        assertThrows(AgentOutcomeUnknownException.class,()->dispatch.execute(server,List.of("probe"),null,false,30));
        verify(queue,times(1)).enqueue(any(),anyString(),anyMap(),anyBoolean(),anyInt(),isNull(),isNull(),isNull());
    }
    @Test void knownFailurePreservesExitCodeAndDoesNotReplay() {
        when(queue.enqueue(eq(server),eq("EXEC"),anyMap(),eq(false),eq(30),isNull(),isNull(),isNull())).thenReturn(task("QUEUED",false));
        when(queue.task("job7",false)).thenReturn(task("FAILED",false));
        assertEquals(4,dispatch.execute(server,List.of("probe"),null,false,30).exitCode());
        verify(queue,times(1)).enqueue(any(),anyString(),anyMap(),anyBoolean(),anyInt(),isNull(),isNull(),isNull());
    }
    @Test void sensitiveOutputIsAlsoSuppressedAtDispatchBoundary() {
        when(queue.enqueue(eq(server),eq("EXEC"),anyMap(),eq(true),eq(30),isNull(),isNull(),isNull())).thenReturn(task("QUEUED",true));
        when(queue.task("job7",false)).thenReturn(task("SUCCEEDED",true));
        var result=dispatch.execute(server,List.of("runtime","set"),"secret".getBytes(),true,30);
        assertEquals("",result.stdout()); assertEquals("",result.stderr()); assertFalse(result.commandDisplay().contains("secret"));
    }
    @Test void noConnectionCannotBePresentedAsReady() {
        when(queue.status(7L)).thenReturn(Map.of("connected",false,"registrationState","ACTIVE"));
        var result=dispatch.checkServer(server);
        assertFalse((boolean)result.get("ready")); assertFalse((boolean)result.get("reachable"));
        verify(queue,never()).enqueue(any(),anyString(),anyMap(),anyBoolean(),anyInt(),any(),any(),any());
    }
    @Test void invalidCommandsAndPathsNeverReachQueue() {
        assertThrows(RemoteExecutionException.class,()->dispatch.execute(server,List.of(),null,false,30));
        assertThrows(RemoteExecutionException.class,()->dispatch.execute(server,List.of("bad\0arg"),null,false,30));
        assertThrows(RemoteExecutionException.class,()->dispatch.copyFile(server,null,"/var/tmp/../etc/passwd"));
        verifyNoInteractions(queue);
    }
}
