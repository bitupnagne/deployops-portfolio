package com.portfolio.deployops.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.portfolio.deployops.domain.ApprovalPolicy;
import com.portfolio.deployops.domain.ApprovalStep;
import com.portfolio.deployops.domain.Project;
import com.portfolio.deployops.domain.User;
import com.portfolio.deployops.domain.UserRole;
import com.portfolio.deployops.dto.ProjectForm;
import com.portfolio.deployops.repository.ApprovalPolicyRepository;
import com.portfolio.deployops.repository.ApprovalStepRepository;
import com.portfolio.deployops.repository.DeployComponentRepository;
import com.portfolio.deployops.repository.ProjectRepository;
import com.portfolio.deployops.repository.ServerGroupRepository;
import com.portfolio.deployops.repository.ServerRepository;
import java.util.Optional;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.http.HttpStatus;
import org.springframework.web.server.ResponseStatusException;
import tools.jackson.databind.json.JsonMapper;

@ExtendWith(MockitoExtension.class)
class ProjectServiceRuntimeSettingsTest {

    @Mock private ProjectRepository projectRepository;
    @Mock private ServerGroupRepository serverGroupRepository;
    @Mock private ServerRepository serverRepository;
    @Mock private ApprovalPolicyRepository approvalPolicyRepository;
    @Mock private ApprovalStepRepository approvalStepRepository;
    @Mock private ProjectImageHistoryService projectImageHistoryService;
    @Mock private ProjectPermissionService projectPermissionService;
    @Mock private DeployComponentRepository deployComponentRepository;
    @InjectMocks private ProjectService projectService;

    private final JsonMapper mapper = JsonMapper.builder().build();
    private Project existing;

    @BeforeEach
    void setUp() {
        existing = Project.builder()
            .id(10L)
            .name("payment-api")
            .dockerNetwork("payment-network")
            .envFilePath("/etc/deploy/payment.env")
            .containerUser("10001:10001")
            .containerCapAdd("NET_BIND_SERVICE")
            .containerPrivileged(false)
            .containerReadOnlyRootFs(true)
            .build();
        when(projectRepository.findById(10L)).thenReturn(Optional.of(existing));
    }

    @Test
    void savingOtherProjectSettingsPreservesOmittedRuntimeFields() {
        Project saved = update("""
            {"name":"payment-api","description":"설명 변경","healthCheckPort":8080}
            """);

        assertThat(saved.getDescription()).isEqualTo("설명 변경");
        assertThat(saved.getHealthCheckPort()).isEqualTo(8080);
        assertRuntimeSettings(saved, "payment-network", "/etc/deploy/payment.env",
            "10001:10001", "NET_BIND_SERVICE");
        assertThat(saved.isContainerPrivileged()).isFalse();
        assertThat(saved.isContainerReadOnlyRootFs()).isTrue();
    }

    @Test
    void suppliedRuntimeValuesReplaceExistingSettings() {
        Project saved = update("""
            {
              "name":"payment-api",
              "dockerNetwork":" new-network ",
              "envFilePath":" /etc/deploy/new.env ",
              "containerUser":" 20001:20001 ",
              "containerCapAdd":" NET_BIND_SERVICE,CHOWN "
            }
            """);

        assertRuntimeSettings(saved, "new-network", "/etc/deploy/new.env",
            "20001:20001", "NET_BIND_SERVICE,CHOWN");
    }

    @ParameterizedTest
    @ValueSource(strings = {"null", "\"\"", "\"   \""})
    void explicitNullOrBlankClearsRuntimeSettings(String value) {
        Project saved = update("""
            {
              "name":"payment-api",
              "dockerNetwork":%1$s,
              "envFilePath":%1$s,
              "containerUser":%1$s,
              "containerCapAdd":%1$s
            }
            """.formatted(value));

        assertRuntimeSettings(saved, null, null, null, null);
    }

    @Test
    void clearingOneRuntimeFieldPreservesTheOtherOmittedFields() {
        Project saved = update("""
            {"name":"payment-api","envFilePath":null}
            """);

        assertRuntimeSettings(saved, "payment-network", null,
            "10001:10001", "NET_BIND_SERVICE");
    }

    @Test
    void internalPresenceFlagsCannotBeSetByTheRequest() {
        Project saved = update("""
            {
              "name":"payment-api",
              "dockerNetworkProvided":true,
              "envFilePathProvided":true,
              "containerUserProvided":true,
              "containerCapAddProvided":true
            }
            """);

        assertRuntimeSettings(saved, "payment-network", "/etc/deploy/payment.env",
            "10001:10001", "NET_BIND_SERVICE");
    }

    @Test
    void assignedPolicyNeedsAnActiveApproverAndIsPersisted() {
        ApprovalPolicy policy = ApprovalPolicy.builder().id(7L).name("운영 승인").build();
        ApprovalStep step = ApprovalStep.builder().id(11L).requiredCount(1).build();
        when(approvalPolicyRepository.findById(7L)).thenReturn(Optional.of(policy));
        when(approvalStepRepository.findByPolicyId(7L)).thenReturn(List.of(step));
        when(approvalStepRepository.findApproversByStepId(11L))
            .thenReturn(List.of(User.builder().id(2L).role(UserRole.APPROVER).active(true).build()));

        Project saved = update("{\"name\":\"payment-api\",\"approvalPolicyId\":7}");

        assertThat(saved.getApprovalPolicy()).isSameAs(policy);
    }

    @Test
    void unknownPolicyCannotSilentlyLeaveProjectUnprotected() {
        when(approvalPolicyRepository.findById(999L)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> projectService.update(10L,
            mapper.readValue("{\"name\":\"payment-api\",\"approvalPolicyId\":999}", ProjectForm.class)))
            .isInstanceOf(ResponseStatusException.class)
            .satisfies(error -> assertThat(((ResponseStatusException) error).getStatusCode())
                .isEqualTo(HttpStatus.BAD_REQUEST));
    }

    @Test
    void legacyPolicyWithoutApprovalStepsCannotBeAssigned() {
        when(approvalPolicyRepository.findById(7L))
            .thenReturn(Optional.of(ApprovalPolicy.builder().id(7L).build()));
        when(approvalStepRepository.findByPolicyId(7L)).thenReturn(List.of());

        assertThatThrownBy(() -> projectService.update(10L,
            mapper.readValue("{\"name\":\"payment-api\",\"approvalPolicyId\":7}", ProjectForm.class)))
            .isInstanceOf(ResponseStatusException.class)
            .satisfies(error -> assertThat(((ResponseStatusException) error).getStatusCode())
                .isEqualTo(HttpStatus.BAD_REQUEST));
    }

    @Test
    void policyWithNoEligibleApproverCannotTrapRequestsInApprovalQueue() {
        when(approvalPolicyRepository.findById(7L))
            .thenReturn(Optional.of(ApprovalPolicy.builder().id(7L).build()));
        when(approvalStepRepository.findByPolicyId(7L))
            .thenReturn(List.of(ApprovalStep.builder().id(11L).requiredCount(1).build()));
        when(approvalStepRepository.findApproversByStepId(11L))
            .thenReturn(List.of(User.builder().id(2L).role(UserRole.DEVELOPER).active(true).build()));

        assertThatThrownBy(() -> projectService.update(10L,
            mapper.readValue("{\"name\":\"payment-api\",\"approvalPolicyId\":7}", ProjectForm.class)))
            .isInstanceOf(ResponseStatusException.class)
            .satisfies(error -> assertThat(((ResponseStatusException) error).getStatusCode())
                .isEqualTo(HttpStatus.BAD_REQUEST));
    }

    private Project update(String json) {
        when(projectRepository.save(any(Project.class))).thenAnswer(invocation -> invocation.getArgument(0));
        Project saved = projectService.update(10L, mapper.readValue(json, ProjectForm.class));
        verify(projectRepository).save(existing);
        return saved;
    }

    private void assertRuntimeSettings(Project project, String network, String envFile,
                                       String user, String capabilities) {
        assertThat(project.getDockerNetwork()).isEqualTo(network);
        assertThat(project.getEnvFilePath()).isEqualTo(envFile);
        assertThat(project.getContainerUser()).isEqualTo(user);
        assertThat(project.getContainerCapAdd()).isEqualTo(capabilities);
    }
}
