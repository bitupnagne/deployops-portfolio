package com.portfolio.deployops.service;

import com.portfolio.deployops.config.SecurityUserDetails;
import com.portfolio.deployops.domain.*;
import com.portfolio.deployops.dto.*;
import com.portfolio.deployops.repository.*;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.web.server.ResponseStatusException;
import java.util.List;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class RuntimeConfigurationServiceTest {
    private final JdbcTemplate jdbc=mock(JdbcTemplate.class);
    private final ProjectRepository projects=mock(ProjectRepository.class);
    private final DeployComponentRepository components=mock(DeployComponentRepository.class);
    private final ProjectPermissionService permissions=mock(ProjectPermissionService.class);
    private final SecretCryptoService crypto=mock(SecretCryptoService.class);
    private final AuditLogService audit=mock(AuditLogService.class);
    private final RuntimeConfigurationService service=new RuntimeConfigurationService(jdbc,projects,components,permissions,crypto,audit);
    private final SecurityUserDetails actor=new SecurityUserDetails(User.builder().id(8L).role(UserRole.OPERATOR).build());

    @Test void dotenvIsLiteralAndNamesOnly() {
        assertEquals(List.of("DB_PASSWORD","TOKEN"),RuntimeConfigurationService.validateContent("TOKEN=$(not-executed)\nDB_PASSWORD=do-not-log=$x\n"));
    }
    @Test void invalidPayloadsNeverReachCryptoOrDatabase() {
        for(String content:List.of("export KEY=value","X=a\nX=b","X=a\rb","X=a\0b","","X=x".repeat(30000)))
            assertThrows(ResponseStatusException.class,()->RuntimeConfigurationService.validateContent(content));
        verifyNoInteractions(crypto,jdbc);
    }
    @Test void explicitNoneOverridesInheritedConfiguration() {
        var base=new ManagedRuntimeSettings(0L,"MANAGED_REVISION",10L,"a",true,32);
        var none=new ManagedRuntimeSettings(4L,"NONE",null,null,false,0);
        assertEquals(base,RuntimeConfigurationService.resolve(List.of(base),4L));
        assertEquals(none,RuntimeConfigurationService.resolve(List.of(base,none),4L));
        assertEquals(base,RuntimeConfigurationService.resolve(List.of(base,new ManagedRuntimeSettings(4L,"INHERIT",null,null,false,0)),4L));
    }
    @Test void forbiddenEnvironmentStopsBeforeScopeAndSecretRead() {
        doThrow(new org.springframework.security.access.AccessDeniedException("denied")).when(permissions).assertPermission(actor,1L,ProjectPermissionType.RUNTIME_CONFIG_MANAGE,DeployEnvironment.PROD);
        assertThrows(org.springframework.security.access.AccessDeniedException.class,()->service.create(1L,DeployEnvironment.PROD,new RuntimeRevisionForm(0L,"SECRET=private","reason"),actor));
        verifyNoInteractions(projects,components,crypto,jdbc,audit);
    }
    @Test void crossProjectComponentIsRefused() {
        when(projects.findById(1L)).thenReturn(java.util.Optional.of(Project.builder().id(1L).build()));
        when(components.findActiveByProjectId(1L)).thenReturn(List.of());
        assertThrows(ResponseStatusException.class,()->service.create(1L,DeployEnvironment.TEST,new RuntimeRevisionForm(99L,"SECRET=private","reason"),actor));
        verifyNoInteractions(crypto,jdbc,audit);
    }
    @Test void inheritedDefaultsCannotPointToRevisionOrStorage() {
        when(projects.findById(1L)).thenReturn(java.util.Optional.of(Project.builder().id(1L).build()));
        assertThrows(ResponseStatusException.class,()->service.bind(1L,DeployEnvironment.TEST,new RuntimeBindingForm(0L,"INHERIT",null,false,0,"reason"),actor));
        verifyNoInteractions(jdbc,audit);
    }
    @Test void invalidRevisionScopeIsRefusedBeforeBinding() {
        when(projects.findById(1L)).thenReturn(java.util.Optional.of(Project.builder().id(1L).build()));
        when(jdbc.queryForObject(anyString(),eq(Integer.class),eq(12L),eq(1L),eq("PROD"),eq(0L))).thenReturn(0);
        assertThrows(ResponseStatusException.class,()->service.bind(1L,DeployEnvironment.PROD,new RuntimeBindingForm(0L,"MANAGED_REVISION",12L,true,32,"reason"),actor));
        verify(jdbc,never()).update(anyString(),any(Object[].class)); verifyNoInteractions(audit);
    }
    @Test void revisionInsertAndAuditNeverReceivePlaintextContentOrReason() throws Exception {
        when(projects.findById(1L)).thenReturn(java.util.Optional.of(Project.builder().id(1L).build()));
        when(components.findActiveByProjectId(1L)).thenReturn(List.of());
        SecretCryptoService actualCrypto=new SecretCryptoService("fixture-only-runtime-key");
        java.sql.Connection connection=mock(java.sql.Connection.class);
        java.sql.PreparedStatement statement=mock(java.sql.PreparedStatement.class);
        when(connection.prepareStatement(anyString(),eq(java.sql.Statement.RETURN_GENERATED_KEYS))).thenReturn(statement);
        when(jdbc.update(any(org.springframework.jdbc.core.PreparedStatementCreator.class),any(org.springframework.jdbc.support.KeyHolder.class))).thenAnswer(call->{
            call.getArgument(0,org.springframework.jdbc.core.PreparedStatementCreator.class).createPreparedStatement(connection);
            call.getArgument(1,org.springframework.jdbc.support.KeyHolder.class).getKeyList().add(java.util.Map.of("id",15L));
            return 1;
        });
        var view=new RuntimeConfigurationService(jdbc,projects,components,permissions,actualCrypto,audit)
            .create(1L,DeployEnvironment.TEST,new RuntimeRevisionForm(0L,"PASSWORD=fixture-private","fixture-private-reason"),actor);
        assertEquals(15L,view.id()); assertEquals(List.of("PASSWORD"),view.keyNames());
        org.mockito.ArgumentCaptor<String> cipher=org.mockito.ArgumentCaptor.forClass(String.class);
        verify(statement).setString(eq(4),cipher.capture());
        assertTrue(cipher.getValue().startsWith("enc:v2:")); assertFalse(cipher.getValue().contains("fixture-private"));
        assertEquals("PASSWORD=fixture-private",actualCrypto.decryptIfEncrypted(cipher.getValue()));
        verify(statement).setString(eq(8),argThat(value->value.startsWith("enc:v2:") && !value.contains("fixture-private")));
        verify(audit).recordCurrentRequired(eq("RUNTIME_REVISION_CREATE"),eq("PROJECT"),eq("1"),argThat(value->!value.contains("fixture-private")),eq(AuditResult.SUCCESS));
    }
    @Test void bindingAuditFailureCannotBeReportedAsSuccess() {
        when(projects.findById(1L)).thenReturn(java.util.Optional.of(Project.builder().id(1L).build()));
        when(components.findActiveByProjectId(1L)).thenReturn(List.of());
        when(crypto.encryptIfPlain("reason")).thenReturn("enc:test-reason");
        doThrow(new IllegalStateException("audit unavailable")).when(audit).recordCurrentRequired(anyString(),anyString(),anyString(),anyString(),eq(AuditResult.SUCCESS));
        assertThrows(IllegalStateException.class,()->service.bind(1L,DeployEnvironment.TEST,new RuntimeBindingForm(0L,"NONE",null,false,0,"reason"),actor));
    }

    @Test void rebindingSameProjectToAnotherRevisionHasDistinctRequiredAuditAndPersistedSelection() {
        when(projects.findById(1L)).thenReturn(java.util.Optional.of(Project.builder().id(1L).build()));
        when(components.findActiveByProjectId(1L)).thenReturn(List.of());
        when(jdbc.queryForObject(anyString(),eq(Integer.class),anyLong(),eq(1L),eq("TEST"),eq(0L))).thenReturn(1);
        when(crypto.encryptIfPlain("private-fixture-reason")).thenReturn("enc:fixture-reason");
        for(long revision : new long[]{1L,5L}) {
            service.bind(1L,DeployEnvironment.TEST,new RuntimeBindingForm(0L,"MANAGED_REVISION",revision,true,64,"private-fixture-reason"),actor);
            verify(jdbc).update(anyString(),eq(1L),eq("TEST"),eq(0L),eq("MANAGED_REVISION"),eq(revision),eq(true),eq(64),eq("enc:fixture-reason"),eq(30));
            verify(audit).recordCurrentRequired("RUNTIME_BINDING_UPDATE","PROJECT","1",
                "environment=TEST,component=0,mode=MANAGED_REVISION,revision="+revision+",stopGraceSeconds=30",AuditResult.SUCCESS);
        }
        verify(audit,never()).recordCurrentRequired(anyString(),anyString(),anyString(),contains("private-fixture-reason"),any());
    }

    @Test void noneBindingExplicitlyAuditsNoSelectedRevisionInsteadOfPreviousManagedRevision() {
        when(projects.findById(1L)).thenReturn(java.util.Optional.of(Project.builder().id(1L).build()));
        when(components.findActiveByProjectId(1L)).thenReturn(List.of());
        when(crypto.encryptIfPlain("reason")).thenReturn("enc:fixture-reason");
        service.bind(1L,DeployEnvironment.PROD,new RuntimeBindingForm(0L,"NONE",null,false,0,"reason"),actor);
        verify(audit).recordCurrentRequired("RUNTIME_BINDING_UPDATE","PROJECT","1",
            "environment=PROD,component=0,mode=NONE,revision=null,stopGraceSeconds=30",AuditResult.SUCCESS);
        verify(jdbc,never()).queryForObject(anyString(),eq(Integer.class),anyLong(),anyLong(),anyString(),anyLong());
    }

    @Test void inheritedComponentAuditsNullableSelectionWithoutPretendingInheritedBaseRevisionWasSelected() {
        when(projects.findById(1L)).thenReturn(java.util.Optional.of(Project.builder().id(1L).build()));
        when(components.findActiveByProjectId(1L)).thenReturn(List.of(DeployComponent.builder().id(8L).artifactType(ArtifactType.CONTAINER).build()));
        service.bind(1L,DeployEnvironment.PROD,new RuntimeBindingForm(8L,"INHERIT",null,false,0,"reason"),actor);
        verify(audit).recordCurrentRequired("RUNTIME_BINDING_UPDATE","PROJECT","1",
            "environment=PROD,component=8,mode=INHERIT,revision=null,stopGraceSeconds=30",AuditResult.SUCCESS);
    }
}
