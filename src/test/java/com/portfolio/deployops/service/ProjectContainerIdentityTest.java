package com.portfolio.deployops.service;

import com.portfolio.deployops.domain.*;
import com.portfolio.deployops.dto.ProjectForm;
import com.portfolio.deployops.repository.*;
import java.util.*;
import java.util.concurrent.atomic.AtomicLong;
import org.junit.jupiter.api.Test;
import org.springframework.web.server.ResponseStatusException;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class ProjectContainerIdentityTest {
    final ProjectRepository repository=mock(ProjectRepository.class);
    final ProjectService service=new ProjectService(repository,mock(ServerGroupRepository.class),
        mock(ServerRepository.class),mock(ApprovalPolicyRepository.class),mock(ApprovalStepRepository.class),
        mock(ProjectImageHistoryService.class),mock(ProjectPermissionService.class),mock(DeployComponentRepository.class));

    ProjectForm form(String name) {var form=new ProjectForm();form.setName(name);return form;}

    @Test void differentKoreanProjectsReceiveDifferentPersistedRuntimeIdentities() {
        var next=new AtomicLong(100);
        when(repository.save(any())).thenAnswer(i -> {
            Project project=i.getArgument(0);project.setId(next.incrementAndGet());return project;
        });
        when(repository.initializeContainerName(anyLong(),anyString())).thenReturn(1);
        var payment=service.create(form("결제 서비스"));
        var employee=service.create(form("직원 서비스"));
        assertThat(ProjectContainerIdentity.resolve(payment)).isEqualTo("deploy-project-101");
        assertThat(ProjectContainerIdentity.resolve(employee)).isEqualTo("deploy-project-102");
        verify(repository).initializeContainerName(101L,"deploy-project-101");
        verify(repository).initializeContainerName(102L,"deploy-project-102");
    }

    @Test void renamingANewProjectDoesNotChangeItsContainerIdentity() {
        var project=Project.builder().id(7L).name("old").containerName("deploy-project-7").build();
        when(repository.findById(7L)).thenReturn(Optional.of(project));
        when(repository.save(any())).thenAnswer(i -> i.getArgument(0));
        var renamed=service.update(7L,form("새 서비스 이름"));
        assertThat(renamed.getName()).isEqualTo("새 서비스 이름");
        assertThat(ProjectContainerIdentity.resolve(renamed)).isEqualTo("deploy-project-7");
        verify(repository,never()).initializeContainerName(anyLong(),anyString());
    }

    @Test void existingProjectUsesLegacyNameAndRenamePinsThatNameWithoutMovingItsContainer() {
        var project=Project.builder().id(7L).name("Legacy Payment").build();
        assertThat(ProjectContainerIdentity.resolve(project)).isEqualTo("legacy-payment");
        assertThat(project.getContainerName()).isNull();
        when(repository.findById(7L)).thenReturn(Optional.of(project));
        when(repository.save(any())).thenAnswer(i -> i.getArgument(0));
        when(repository.initializeContainerName(7L,"legacy-payment")).thenReturn(1);
        var renamed=service.update(7L,form("새 서비스 이름"));
        assertThat(ProjectContainerIdentity.resolve(renamed)).isEqualTo("legacy-payment");
        verify(repository).initializeContainerName(7L,"legacy-payment");
    }

    @Test void legacyKoreanCollisionBlocksIncludingInactiveProjects() {
        var project=Project.builder().id(1L).name("결제").build();
        var other=Project.builder().id(2L).name("직원").active(false).build();
        var server=Server.builder().id(8L).ipAddress("198.18.8.8").build();
        when(repository.findSharingTargetServer(8L,"198.18.8.8")).thenReturn(List.of(project,other));
        var isolation=new ProjectRuntimeIsolationService(repository);
        assertThatThrownBy(() -> isolation.assertIsolated(project,server))
            .isInstanceOf(ResponseStatusException.class).hasMessageContaining("이관").hasMessageContaining("ID 2");
        other.setContainerName("deploy-project-2");
        assertThatCode(() -> isolation.assertIsolated(project,server)).doesNotThrowAnyException();
    }

    @Test void approvedHistoricalNameIsCheckedAgainstCurrentOtherProjectMappings() {
        var frozen=Project.builder().id(1L).name("old-name").build();
        var other=Project.builder().id(2L).name("different").containerName("old-name").build();
        var server=Server.builder().id(8L).ipAddress("198.18.8.8").build();
        when(repository.findSharingTargetServer(8L,"198.18.8.8")).thenReturn(List.of(other));
        assertThatThrownBy(() -> new ProjectRuntimeIsolationService(repository).assertIsolated(frozen,server))
            .isInstanceOf(ResponseStatusException.class);
    }

    @Test void mapperReadsIdentityButOrdinaryUpdatesCannotRenameRuntime() {
        var configuration=new org.apache.ibatis.session.Configuration();
        configuration.addMapper(ProjectRepository.class);
        String prefix=ProjectRepository.class.getName()+".";
        var named=Project.builder().id(7L).name("name").containerName("changed-name").build();
        assertThat(configuration.getMappedStatement(prefix+"findById").getBoundSql(7L).getSql())
            .contains("p.container_name");
        assertThat(configuration.getMappedStatement(prefix+"update").getBoundSql(named).getSql())
            .doesNotContain("container_name");
        assertThat(configuration.getMappedStatement(prefix+"initializeContainerName")
            .getBoundSql(Map.of("id",7L,"containerName","deploy-project-7")).getSql())
            .contains("container_name IS NULL");
        String sharing=configuration.getMappedStatement(prefix+"findSharingTargetServer")
            .getBoundSql(Map.of("serverId",8L,"ipAddress","198.18.8.8")).getSql();
        assertThat(sharing).contains("s.id =", "s.ip_address =").doesNotContain("p.active =");
    }
}
