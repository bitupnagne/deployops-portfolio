package com.portfolio.deployops.service;

import com.portfolio.deployops.config.SecurityUserDetails;
import com.portfolio.deployops.domain.*;
import com.portfolio.deployops.dto.*;
import com.portfolio.deployops.repository.*;
import java.util.*;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.web.server.ResponseStatusException;
import tools.jackson.databind.json.JsonMapper;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class StorageMountServiceTest {
    final JdbcTemplate jdbc=mock(JdbcTemplate.class);
    final ServerRepository servers=mock(ServerRepository.class);
    final ProjectRepository projects=mock(ProjectRepository.class);
    final DeployComponentRepository components=mock(DeployComponentRepository.class);
    final ProjectPermissionService permissions=mock(ProjectPermissionService.class);
    final SshCommandService ssh=mock(SshCommandService.class);
    final AuditLogService audit=mock(AuditLogService.class);
    final PlatformTransactionManager transactions=mock(PlatformTransactionManager.class);
    final StorageMountService service=spy(new StorageMountService(jdbc,servers,projects,components,permissions,ssh,JsonMapper.builder().build(),audit,transactions));
    final SecurityUserDetails operator=new SecurityUserDetails(User.builder().id(8L).role(UserRole.OPERATOR).build());
    final SecurityUserDetails admin=new SecurityUserDetails(User.builder().id(1L).role(UserRole.SUPER_ADMIN).build());
    final Server was=Server.builder().id(11L).serverType(ServerType.WAS).environment(ServerEnvironment.TEST).build();
    final ServerStorageLocation location=new ServerStorageLocation(11L,"shared","NAS","/mnt/shared","/mnt/shared","nas:/export","nfs4",true);
    final Project project=Project.builder().id(1L).containerUser("1000:1000").build();

    @Test void onlyAdminCanInspectOrRegisterHostPaths() {
        assertEquals(403,assertThrows(ResponseStatusException.class,()->service.inspect(11L,"/mnt/shared","NAS",operator)).getStatusCode().value());
        assertEquals(403,assertThrows(ResponseStatusException.class,()->service.saveLocation(11L,new StorageLocationForm("shared","NAS","/mnt/shared","/mnt/shared","nas:/export","nfs4",true,"NAS 등록"),operator)).getStatusCode().value());
        verifyNoInteractions(ssh,jdbc,servers);
    }
    @Test void pathTraversalSensitiveMountsAndNestedTargetsAreRejected() {
        for(String path:List.of("/etc","/mnt/../etc","/var/lib/docker/volumes","/usr/share","/mnt//data","/data/","/data,$(id)")) assertThrows(ResponseStatusException.class,()->StorageMountPaths.root(path));
        for(String path:List.of("../data","a/../../etc","/absolute","a/./b","x\\y")) assertThrows(ResponseStatusException.class,()->StorageMountPaths.relative(path));
        assertThrows(ResponseStatusException.class,()->StorageMountService.validateBindingPaths(List.of(new StorageBindingForm.Mount("shared","","/app/data",true),new StorageBindingForm.Mount("shared","sub","/app/data/sub",false))));
        assertDoesNotThrow(()->StorageMountService.validateBindingPaths(List.of(new StorageBindingForm.Mount("shared","orders","/app/data",true))));
    }
    @Test void forgedEnvironmentPermissionFailsBeforeAnyConfigurationRead() {
        doThrow(new org.springframework.security.access.AccessDeniedException("denied")).when(permissions).assertPermission(operator,1L,ProjectPermissionType.RUNTIME_CONFIG_MANAGE,DeployEnvironment.PROD);
        assertThrows(org.springframework.security.access.AccessDeniedException.class,()->service.bind(1L,DeployEnvironment.PROD,new StorageBindingForm(0L,List.of(),"remove"),operator));
        verifyNoInteractions(projects,components,jdbc,ssh);
    }
    @Test void changeReasonIsRequiredAndMaskedInAuditForBothInventories() {
        when(projects.findById(1L)).thenReturn(Optional.of(project));
        when(components.findActiveByProjectId(1L)).thenReturn(List.of());
        service.bind(1L,DeployEnvironment.TEST,new StorageBindingForm(0L,List.of(),"경로 변경 token=do-not-store"),operator);
        var description=org.mockito.ArgumentCaptor.forClass(String.class);
        verify(audit).recordCurrentRequired(eq("STORAGE_BINDING_UPDATE"),eq("PROJECT"),eq("1"),description.capture(),eq(AuditResult.SUCCESS));
        assertTrue(description.getValue().contains("경로 변경 token=***")); assertFalse(description.getValue().contains("do-not-store"));
        when(servers.findById(11L)).thenReturn(Optional.of(was)); doReturn(List.of()).when(service).locations(11L);
        when(ssh.executeSensitive(eq(was),anyList(),any(byte[].class))).thenReturn(new RemoteCommandResult(0,"","","",null,null,0));
        service.saveLocation(11L,new StorageLocationForm("shared","NAS","/mnt/shared","/mnt/shared","nas:/export","nfs4",true,"마운트 변경 password=do-not-store"),admin);
        verify(audit).recordCurrentRequired(eq("STORAGE_LOCATION_UPDATE"),eq("SERVER"),eq("11"),description.capture(),eq(AuditResult.SUCCESS));
        assertTrue(description.getValue().contains("password=***")); assertFalse(description.getValue().contains("do-not-store"));
        assertThrows(ResponseStatusException.class,()->service.saveLocation(11L,new StorageLocationForm("shared","NAS","/mnt/shared","/mnt/shared","nas:/export","nfs4",true,null),admin));
    }
    @Test void foreignOrWebComponentCannotBeBound() {
        when(projects.findById(1L)).thenReturn(Optional.of(project));
        when(components.findActiveByProjectId(1L)).thenReturn(List.of(DeployComponent.builder().id(5L).role(ServerType.WEB).artifactType(ArtifactType.CONTAINER).build()));
        assertThrows(ResponseStatusException.class,()->service.bind(1L,DeployEnvironment.TEST,new StorageBindingForm(5L,List.of(),"reason"),operator));
        verifyNoInteractions(jdbc,ssh);
    }
    @Test void failedNativeRegistrationNeverWritesDatabaseOrSuccessAudit() {
        when(servers.findById(11L)).thenReturn(Optional.of(was)); doReturn(List.of()).when(service).locations(11L);
        when(ssh.executeSensitive(eq(was),anyList(),any(byte[].class))).thenReturn(new RemoteCommandResult(1,"","","",null,null,0));
        assertThrows(ResponseStatusException.class,()->service.saveLocation(11L,new StorageLocationForm("shared","NAS","/mnt/shared","/mnt/shared","nas:/export","nfs4",true,"NAS 등록"),admin));
        verify(jdbc,never()).update(anyString(),any(Object[].class)); verifyNoInteractions(audit);
    }
    @Test void failedBindingCheckExplainsContainerPermissionsWithoutLeakingRemoteDetailsOrChangingBindings() {
        when(projects.findById(1L)).thenReturn(Optional.of(project));
        when(components.findActiveByProjectId(1L)).thenReturn(List.of());
        var group=ServerGroup.builder().id(2L).environment(ServerEnvironment.TEST).build();
        when(projects.findServerGroupsByProjectId(1L)).thenReturn(List.of(group));
        when(servers.findByServerGroupId(2L)).thenReturn(List.of(was));
        doReturn(List.of(new StorageMountService.Binding(0L,"shared","orders","/app/data",false)))
            .when(service).bindings(1L,DeployEnvironment.TEST);
        doReturn(List.of(location)).when(service).locations(11L);
        when(ssh.executeSensitive(eq(was),anyList(),any(byte[].class)))
            .thenReturn(new RemoteCommandResult(1,"secret stdout","secret stderr","",null,null,0))
            .thenThrow(new IllegalStateException("secret connection details"));
        for (int attempt=0;attempt<2;attempt++) {
            var error=assertThrows(ResponseStatusException.class,
                ()->service.checkBinding(1L,DeployEnvironment.TEST,0L,11L,operator));
            assertEquals(409,error.getStatusCode().value());
            assertTrue(error.getReason().contains("UID:GID"));
            assertTrue(error.getReason().contains("읽기·쓰기(rw)"));
            assertTrue(error.getReason().contains("검사를 다시 실행"));
            assertTrue(error.getReason().contains("저장된 연결은 변경하지 않았습니다"));
            assertFalse(error.getReason().contains("secret"));
            assertFalse(error.getReason().contains("쓰기 권한이 없습니다"));
        }
        verify(ssh,times(2)).executeSensitive(eq(was),eq(List.of("sudo",StorageMountService.HELPER,"check")),any(byte[].class));
        verifyNoInteractions(jdbc,audit);
    }
    @Test void concurrentInventoryChangeAfterNativeCompletionDoesNotPublishStaleLocalState() {
        when(servers.findById(11L)).thenReturn(Optional.of(was));
        var changed=new ServerStorageLocation(11L,"shared","NAS","/mnt/shared","/mnt/shared","other:/export","nfs4",true);
        doReturn(List.of(location),List.of(changed)).when(service).locations(11L);
        when(ssh.executeSensitive(eq(was),anyList(),any(byte[].class))).thenReturn(new RemoteCommandResult(0,"","","",null,null,0));
        assertTrue(assertThrows(ResponseStatusException.class,()->service.saveLocation(11L,new StorageLocationForm("shared","NAS","/mnt/shared","/mnt/shared","nas:/export","nfs4",true,"NAS 확인"),admin)).getReason().contains("변경"));
        verify(jdbc,never()).update(anyString(),any(Object[].class)); verifyNoInteractions(audit);
    }
    @Test void changedAgentBindingAfterNativeCompletionDoesNotPublishRegistration() {
        var before=Server.builder().id(11L).serverType(ServerType.WAS).environment(ServerEnvironment.TEST)
            .controlMode(ServerControlMode.AGENT).agentBindingId("before").build();
        var changed=Server.builder().id(11L).serverType(ServerType.WAS).environment(ServerEnvironment.TEST)
            .controlMode(ServerControlMode.AGENT).agentBindingId("after").build();
        when(servers.findById(11L)).thenReturn(Optional.of(before),Optional.of(changed));
        doReturn(List.of()).when(service).locations(11L);
        when(ssh.executeSensitive(eq(before),anyList(),any(byte[].class))).thenReturn(new RemoteCommandResult(0,"","","",null,null,0));
        assertThrows(ResponseStatusException.class,()->service.saveLocation(11L,new StorageLocationForm("shared","NAS","/mnt/shared","/mnt/shared","nas:/export","nfs4",true,"NAS 확인"),admin));
        verify(jdbc,never()).update(anyString(),any(Object[].class)); verifyNoInteractions(audit);
    }
    @Test void selectedHostMappingIsFrozenAndMissingOrChangedNasFailsClosed() {
        doReturn(List.of(new StorageMountService.Binding(0L,"shared","orders","/app/data",false))).when(service).bindings(1L,DeployEnvironment.TEST);
        doReturn(List.of(location)).when(service).locations(11L);
        var group=ServerGroup.builder().id(2L).environment(ServerEnvironment.TEST).servers(List.of(was)).build();
        var resolved=service.describe(project,DeployEnvironment.TEST,List.of(group),List.of(),DeployScope.WAS_ONLY);
        assertEquals(1,resolved.size()); assertEquals("nas:/export",resolved.getFirst().mountSource()); assertFalse(resolved.getFirst().readOnly());
        assertDoesNotThrow(()->service.assertPinnedAllowed(resolved));
        doReturn(List.of(new ServerStorageLocation(11L,"shared","NAS","/mnt/shared","/mnt/shared","other:/export","nfs4",true))).when(service).locations(11L);
        assertThrows(ResponseStatusException.class,()->service.assertPinnedAllowed(resolved));
        doReturn(List.of()).when(service).locations(11L);
        assertThrows(ResponseStatusException.class,()->service.describe(project,DeployEnvironment.TEST,List.of(group),List.of(),DeployScope.WAS_ONLY));
    }
    @Test void webOnlyScopeDoesNotInventWasMounts() {
        doReturn(List.of(new StorageMountService.Binding(0L,"shared","","/app/data",true))).when(service).bindings(1L,DeployEnvironment.TEST);
        assertNull(service.describe(project,DeployEnvironment.TEST,List.of(ServerGroup.builder().id(2L).environment(ServerEnvironment.TEST).servers(List.of(was)).build()),List.of(),DeployScope.WEB_ONLY));
        verify(servers,never()).findById(anyLong());
    }
    @Test void imageRootUserAndCapabilitiesCannotObtainHostStorage() {
        assertThrows(ResponseStatusException.class,()->StorageMountService.validateContainer(Project.builder().build(),null));
        assertThrows(ResponseStatusException.class,()->StorageMountService.validateContainer(Project.builder().containerUser("0:0").build(),null));
        assertThrows(ResponseStatusException.class,()->StorageMountService.validateContainer(Project.builder().containerUser("1000:1000").containerCapAdd("SYS_ADMIN").build(),null));
        assertDoesNotThrow(()->StorageMountService.validateContainer(project,null));
    }
    @Test void nativeSpecContainsPinnedMountsWithoutSecretsAndIsIsolatedByServerAndComponent() throws Exception {
        var mount=new StorageMountSettings(11L,5L,"shared","NAS","/mnt/shared","orders","/app/data",true,"/mnt/shared","nas:/export","nfs4");
        project.setStorageMounts(List.of(mount));
        assertEquals(List.of(mount),StorageMountService.pinnedFor(project,was,5L));
        assertTrue(StorageMountService.pinnedFor(project,was,6L).isEmpty());
        assertTrue(StorageMountService.pinnedFor(project,Server.builder().id(12L).build(),5L).isEmpty());
        var spec=StorageMountService.spec(1L,DeployEnvironment.TEST,5L,was,7L,"project-api","1000:1000",List.of(mount));
        assertEquals(7L,spec.get("requestId")); assertEquals("1000:1000",spec.get("containerUser"));
        assertFalse(JsonMapper.builder().build().writeValueAsString(spec).contains("password"));
    }
}
