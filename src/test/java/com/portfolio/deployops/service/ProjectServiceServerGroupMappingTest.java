package com.portfolio.deployops.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.portfolio.deployops.domain.Project;
import com.portfolio.deployops.domain.ServerEnvironment;
import com.portfolio.deployops.domain.ServerGroup;
import com.portfolio.deployops.repository.ApprovalPolicyRepository;
import com.portfolio.deployops.repository.ApprovalStepRepository;
import com.portfolio.deployops.repository.DeployComponentRepository;
import com.portfolio.deployops.repository.ProjectRepository;
import com.portfolio.deployops.repository.ServerRepository;
import com.portfolio.deployops.repository.ServerGroupRepository;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.web.server.ResponseStatusException;

@ExtendWith(MockitoExtension.class)
class ProjectServiceServerGroupMappingTest {

    @Mock private ProjectRepository projectRepository;
    @Mock private ServerGroupRepository serverGroupRepository;
    @Mock private ServerRepository serverRepository;
    @Mock private ApprovalPolicyRepository approvalPolicyRepository;
    @Mock private ApprovalStepRepository approvalStepRepository;
    @Mock private ProjectImageHistoryService projectImageHistoryService;
    @Mock private ProjectPermissionService projectPermissionService;
    @Mock private DeployComponentRepository deployComponentRepository;
    @InjectMocks private ProjectService projectService;

    @BeforeEach
    void projectExists() {
        when(projectRepository.findById(10L)).thenReturn(Optional.of(Project.builder().id(10L).build()));
    }

    @Test
    void replacesWithSortedDistinctExistingGroupsAndAllowsExplicitClear() {
        when(serverGroupRepository.findAllById(List.of(2L, 7L))).thenReturn(List.of(group(2L), group(7L)));

        assertThat(projectService.replaceServerGroupMappings(10L, List.of(7L, 2L))).containsExactly(2L, 7L);
        verify(projectRepository).replaceServerGroupMappings(10L, List.of(2L, 7L));

        assertThat(projectService.replaceServerGroupMappings(10L, List.of())).isEmpty();
        verify(projectRepository).replaceServerGroupMappings(10L, List.of());
    }

    @Test
    void rejectsMissingDuplicateAndNullIdsBeforeReplacingMappings() {
        when(serverGroupRepository.findAllById(List.of(2L, 7L))).thenReturn(List.of(group(2L)));

        assertThatThrownBy(() -> projectService.replaceServerGroupMappings(10L, List.of(2L, 7L)))
            .isInstanceOf(ResponseStatusException.class);
        assertThatThrownBy(() -> projectService.replaceServerGroupMappings(10L, List.of(2L, 2L)))
            .isInstanceOf(ResponseStatusException.class);
        assertThatThrownBy(() -> projectService.replaceServerGroupMappings(10L, java.util.Arrays.asList(2L, null)))
            .isInstanceOf(ResponseStatusException.class);

        verify(projectRepository, never()).replaceServerGroupMappings(10L, List.of(2L, 7L));
    }

    private ServerGroup group(Long id) {
        return ServerGroup.builder().id(id).name("group-" + id).environment(ServerEnvironment.TEST).build();
    }
}
