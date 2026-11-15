package com.portfolio.deployops.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.portfolio.deployops.domain.ContainerPortBindings;
import com.portfolio.deployops.domain.Project;
import com.portfolio.deployops.dto.ProjectForm;
import com.portfolio.deployops.repository.*;
import java.util.List;
import java.util.Optional;
import org.apache.ibatis.session.Configuration;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.http.HttpStatus;
import org.springframework.web.server.ResponseStatusException;
import tools.jackson.databind.json.JsonMapper;

class ProjectContainerPortsTest {
    private final ProjectRepository projects = mock(ProjectRepository.class);
    private final ProjectService service = new ProjectService(projects, mock(ServerGroupRepository.class), mock(ServerRepository.class),
        mock(ApprovalPolicyRepository.class), mock(ApprovalStepRepository.class), mock(ProjectImageHistoryService.class),
        mock(ProjectPermissionService.class), mock(DeployComponentRepository.class));
    private final JsonMapper json = JsonMapper.builder().build();
    private final Project existing = Project.builder().id(7L).name("demo-app").containerPorts("9080:9080,9090:9090").build();

    @BeforeEach
    void setUp() {
        when(projects.initializeContainerName(org.mockito.ArgumentMatchers.anyLong(),org.mockito.ArgumentMatchers.anyString())).thenReturn(1);
        when(projects.findById(7L)).thenReturn(Optional.of(existing));
        when(projects.save(any())).thenAnswer(invocation -> {
            Project project = invocation.getArgument(0);
            project.setId(7L);
            return project;
        });
    }

    @Test
    void createAndUpdateNormalizeNumericTcpPortsWithoutChangingTheHealthPort() {
        ProjectForm form = json.readValue("""
            {"name":"demo-app","containerPorts":" 09090 : 9090 , 9080:9080 ","healthCheckPort":9090}
            """, ProjectForm.class);
        assertThat(service.create(form).getContainerPorts()).isEqualTo("9080:9080,9090:9090");
        Project saved = service.update(7L, form);
        assertThat(saved.getContainerPorts()).isEqualTo("9080:9080,9090:9090");
        assertThat(saved.getHealthCheckPort()).isEqualTo(9090);
        assertThat(ContainerPortBindings.bindings("65535:1,1:65535")).containsExactly("1:65535", "65535:1");
    }

    @Test
    void omittedPropertyAndSpoofedPresenceFlagPreserveExistingBindings() {
        service.update(7L, json.readValue("{\"name\":\"demo-app\",\"containerPortsProvided\":true}", ProjectForm.class));
        assertThat(existing.getContainerPorts()).isEqualTo("9080:9080,9090:9090");
        assertThat(service.create(json.readValue("{\"name\":\"legacy\"}", ProjectForm.class)).getContainerPorts()).isNull();
    }

    @ParameterizedTest
    @ValueSource(strings = {"null", "\"\"", "\"   \""})
    void explicitEmptyClearsBindingsAndReturnsToLegacyMapping(String value) {
        service.update(7L, json.readValue("{\"name\":\"demo-app\",\"containerPorts\":" + value + "}", ProjectForm.class));
        assertThat(existing.getContainerPorts()).isNull();
    }

    @ParameterizedTest
    @ValueSource(strings = {"0:80", "65536:80", "80:0", "80:65536", "9090:80,09090:81", "9090:9090,",
        "127.0.0.1:9090:9090", "9090:9090/udp", "9090:9090/tcp", "9080-9090:80", "--privileged", "9090:9090 --network host"})
    void invalidOrAmbiguousBindingsAreRejectedBeforeSaving(String value) {
        ProjectForm form = new ProjectForm();
        form.setContainerPorts(value);
        assertThatThrownBy(() -> service.update(7L, form))
            .isInstanceOfSatisfying(ResponseStatusException.class,
                error -> assertThat(error.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST));
        verify(projects, never()).save(any());
    }

    @Test
    void actualMybatisMappingsReadInsertAndUpdateTheNewColumn() {
        Configuration config = new Configuration();
        config.addMapper(ProjectRepository.class);
        String prefix = ProjectRepository.class.getName() + ".";
        assertThat(config.getMappedStatement(prefix + "findById").getBoundSql(7L).getSql()).contains("p.container_ports");
        assertThat(config.getResultMap(prefix + "projectResult").getResultMappings())
            .anySatisfy(mapping -> {
                assertThat(mapping.getProperty()).isEqualTo("containerPorts");
                assertThat(mapping.getColumn()).isEqualTo("container_ports");
            });
        for (String operation : List.of("insert", "update")) {
            var sql = config.getMappedStatement(prefix + operation).getBoundSql(existing);
            assertThat(sql.getSql()).contains("container_ports");
            assertThat(sql.getParameterMappings()).anySatisfy(mapping -> assertThat(mapping.getProperty()).isEqualTo("containerPorts"));
        }
    }
}
