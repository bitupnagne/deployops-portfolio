package com.portfolio.deployops.service;

import com.portfolio.deployops.config.SecurityUserDetails;
import com.portfolio.deployops.domain.*;
import com.portfolio.deployops.repository.*;
import java.util.List;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class RuntimeLogServiceTest {
    private final ProjectPermissionService permissions=mock(ProjectPermissionService.class);
    private final ProjectRepository projects=mock(ProjectRepository.class);
    private final ServerRepository servers=mock(ServerRepository.class);
    private final DeployComponentRepository components=mock(DeployComponentRepository.class);
    private final RuntimeConfigurationService configuration=mock(RuntimeConfigurationService.class);
    private final SshCommandService ssh=mock(SshCommandService.class);
    private final AuditLogService audit=mock(AuditLogService.class);
    private final RuntimeLogService service=new RuntimeLogService(permissions,projects,servers,components,configuration,ssh,tools.jackson.databind.json.JsonMapper.builder().build(),audit);
    private final SecurityUserDetails actor=new SecurityUserDetails(User.builder().id(4L).role(UserRole.OPERATOR).build());
    @Test void logPermissionIsEnvironmentScopedAndRequiredBeforeRemoteRead() {
        doThrow(new org.springframework.security.access.AccessDeniedException("denied")).when(permissions).assertPermission(actor,1L,ProjectPermissionType.RUNTIME_LOG_READ,DeployEnvironment.PROD);
        assertThrows(org.springframework.security.access.AccessDeniedException.class,()->service.read(1L,DeployEnvironment.PROD,10L,0L,200,actor));
        verifyNoInteractions(projects,ssh,audit);
    }
    @Test void unmappedServerIsRefusedBeforeRemoteRead() {
        when(projects.findById(1L)).thenReturn(java.util.Optional.of(Project.builder().id(1L).build()));
        when(projects.findServerGroupsByProjectId(1L)).thenReturn(List.of());
        assertThrows(org.springframework.web.server.ResponseStatusException.class,()->service.read(1L,DeployEnvironment.TEST,10L,0L,200,actor));
        verifyNoInteractions(ssh,audit);
    }
    @Test void fixedReaderReturnsTextAndNeverUsesSubmittedHostPath() {
        when(projects.findById(1L)).thenReturn(java.util.Optional.of(Project.builder().id(1L).containerUser("10001:10001").build()));
        when(projects.findServerGroupsByProjectId(1L)).thenReturn(List.of(ServerGroup.builder().id(3L).environment(ServerEnvironment.TEST).build()));
        Server server=Server.builder().id(10L).environment(ServerEnvironment.TEST).serverType(ServerType.WAS).deployAccount("deploy").build();
        when(servers.findByServerGroupId(3L)).thenReturn(List.of(server));
        when(components.findActiveByProjectId(1L)).thenReturn(List.of());
        when(configuration.bindings(1L,DeployEnvironment.TEST)).thenReturn(List.of(new ManagedRuntimeSettings(0L,"NONE",null,null,true,0)));
        when(ssh.execute(eq(server),any())).thenReturn(new RemoteCommandResult(0,"{\"text\":\"<script>literal</script>\",\"truncated\":true}","","",null,null,1));
        var result=service.read(1L,DeployEnvironment.TEST,10L,0L,20,actor);
        assertEquals("<script>literal</script>",result.text()); assertTrue(result.truncated());
        verify(ssh).execute(eq(server),argThat(args->args.contains("/usr/local/deploy/bin/deploy-runtime.sh") && args.contains("logs") && !args.contains("/etc/passwd")));
        verify(audit).recordCurrentRequired(eq("RUNTIME_LOG_READ"),eq("PROJECT"),eq("1"),anyString(),eq(AuditResult.SUCCESS));
    }
}
