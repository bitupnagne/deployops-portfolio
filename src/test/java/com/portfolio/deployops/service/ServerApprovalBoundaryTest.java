package com.portfolio.deployops.service;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;
import com.portfolio.deployops.domain.*;
import com.portfolio.deployops.dto.ServerForm;
import com.portfolio.deployops.repository.*;
import java.util.*;
import java.util.function.Consumer;
import org.junit.jupiter.api.*;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.http.HttpStatus;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.web.server.ResponseStatusException;

class ServerApprovalBoundaryTest {
    final ServerRepository servers = mock(ServerRepository.class);
    final ServerGroupRepository groups = mock(ServerGroupRepository.class);
    final RemoteCommandLogRepository logs = mock(RemoteCommandLogRepository.class);
    final ManualServerExecutionRepository manual = mock(ManualServerExecutionRepository.class);
    final ServerService service = new ServerService(servers, logs, manual);
    {
        var agents = mock(AgentQueueService.class);
        service.configureAgentQueue(agents);
        when(agents.status(anyLong())).thenReturn(java.util.Map.of("registrationState", "NOT_REGISTERED"));
    }
    Server protectedServer;
    ServerGroup protectedGroup;
    ServerGroup ordinaryGroup;

    @BeforeEach void setup() {
        actor("OPERATOR");
        protectedGroup = ServerGroup.builder().id(1L).environment(ServerEnvironment.TEST).approvalPolicyId(4L).build();
        ordinaryGroup = ServerGroup.builder().id(2L).environment(ServerEnvironment.TEST).build();
        protectedServer = Server.builder().id(7L).name("protected").ipAddress("10.1.2.3")
            .sshPort(2022).deployAccount("deploy").authType(AuthType.SSH_KEY)
            .serverType(ServerType.WAS).environment(ServerEnvironment.TEST).serverGroup(protectedGroup).build();
        when(groups.findByIdForUpdate(1L)).thenReturn(Optional.of(protectedGroup));
        when(groups.findByIdForUpdate(2L)).thenReturn(Optional.of(ordinaryGroup));
        when(servers.findByIdForMutation(7L)).thenReturn(Optional.of(protectedServer));
        when(servers.findAllForMutation()).thenReturn(List.of(protectedServer));
        when(servers.save(any())).thenAnswer(call -> call.getArgument(0));
    }
    @AfterEach void cleanup() { SecurityContextHolder.clearContext(); }
    void actor(String role) {
        SecurityContextHolder.getContext().setAuthentication(new UsernamePasswordAuthenticationToken("qa", "unused",
            List.of(new SimpleGrantedAuthority("ROLE_" + role))));
    }
    ServerForm form(Long groupId, String ip) {
        var form = new ServerForm();
        form.setName("renamed"); form.setServerGroupId(groupId); form.setIpAddress(ip);
        form.setEnvironment(ServerEnvironment.TEST); form.setServerType(ServerType.WAS);
        form.setSshPort(2022); form.setDeployAccount("deploy"); form.setAuthType(AuthType.SSH_KEY);
        return form;
    }
    void rejected(Runnable operation, HttpStatus status) {
        assertThatThrownBy(operation::run).isInstanceOfSatisfying(ResponseStatusException.class,
            e -> assertThat(e.getStatusCode()).isEqualTo(status));
        verify(servers, never()).save(any());
        verify(servers, never()).delete(any());
    }
    @Test void operatorMayRenameAndReorderProtectedServerWithoutChangingApprovalBoundary() {
        var form = form(1L, "10.1.2.3"); form.setMemo("점검 메모"); form.setOrderIndex(3);
        var saved = service.update(7L, form, groups);
        assertThat(saved.getName()).isEqualTo("renamed");
        assertThat(saved.getMemo()).isEqualTo("점검 메모");
        assertThat(saved.getOrderIndex()).isEqualTo(3);
        assertThat(saved.getServerGroup().getApprovalPolicyId()).isEqualTo(4L);
        var order = inOrder(servers);
        order.verify(servers).lockGroupsForServerMutation();
        order.verify(servers).findByIdForMutation(7L);
        order.verify(servers).save(protectedServer);
    }
    @Test void protectedServerCannotBeMovedDetachedOrHaveItsEndpointChanged() {
        List<Consumer<ServerForm>> mutations = List.of(
            f -> f.setServerGroupId(2L), f -> f.setServerGroupId(null),
            f -> f.setIpAddress("10.1.2.4"), f -> f.setSshPort(22),
            f -> f.setDeployAccount("root"), f -> f.setAuthType(AuthType.PASSWORD),
            f -> f.setServerType(ServerType.WEB));
        for (var mutation : mutations) {
            var form = form(1L, "10.1.2.3"); mutation.accept(form);
            rejected(() -> service.update(7L, form, groups), HttpStatus.FORBIDDEN);
            assertThat(protectedServer.getName()).isEqualTo("protected");
        }
    }
    @Test void protectedDeleteRequiresAdministratorEvenWithoutHistory() {
        rejected(() -> service.delete(7L), HttpStatus.FORBIDDEN);
        verifyNoInteractions(logs, manual);
        actor("SUPER_ADMIN");
        service.delete(7L);
        verify(servers).delete(protectedServer);
    }
    @ParameterizedTest
    @ValueSource(strings = {"10.1.2.3", "::ffff:10.1.2.3", "::ffff:a01:203", "[::ffff:10.1.2.3]"})
    void aliasRegistrationCannotBypassApprovalUsingPortAccountOrEnvironment(String ip) {
        var form = form(null, ip); form.setEnvironment(ServerEnvironment.PROD);
        form.setSshPort(22); form.setDeployAccount("another");
        rejected(() -> service.create(form, groups), HttpStatus.FORBIDDEN);
    }
    @ParameterizedTest
    @ValueSource(strings = {"127.0.0.1", "127.10.20.30", "::1", "localhost", "LOCALHOST.", "::ffff:127.0.0.2"})
    void loopbackAliasesShareOneProtectedIdentity(String ip) {
        protectedServer.setIpAddress("::1");
        rejected(() -> service.create(form(2L, ip), groups), HttpStatus.FORBIDDEN);
    }
    @ParameterizedTest
    @ValueSource(strings = {"010.1.2.3", "10.1.2", "167838211", "vm.company.internal", "fe80::1%eth0", "::ffff:010.1.2.3"})
    void ambiguousOrDnsAddressesRequireAdministrator(String ip) {
        rejected(() -> service.create(form(2L, ip), groups), HttpStatus.BAD_REQUEST);
    }
    @Test void unprotectedServerCannotBeRepointedAtProtectedHost() {
        var ordinary = Server.builder().id(8L).name("ordinary").ipAddress("10.1.2.4")
            .serverGroup(ordinaryGroup).sshPort(2022).deployAccount("deploy").authType(AuthType.SSH_KEY).build();
        when(servers.findByIdForMutation(8L)).thenReturn(Optional.of(ordinary));
        rejected(() -> service.update(8L, form(2L, "10.1.2.3"), groups), HttpStatus.FORBIDDEN);
        assertThat(ordinary.getIpAddress()).isEqualTo("10.1.2.4");
    }
    @Test void legacyProtectedDnsAllowsMetadataButFailsClosedForNewEndpoint() {
        protectedServer.setIpAddress("legacy.internal");
        assertThat(service.update(7L, form(1L, "legacy.internal"), groups).getName()).isEqualTo("renamed");
        clearInvocations(servers);
        rejected(() -> service.create(form(2L, "10.1.2.99"), groups), HttpStatus.FORBIDDEN);
    }
    @Test void ordinaryDistinctHostAndAdministratorMigrationRemainAllowed() {
        assertThat(service.create(form(2L, "10.1.2.99"), groups).getIpAddress()).isEqualTo("10.1.2.99");
        actor("SUPER_ADMIN");
        assertThat(service.update(7L, form(2L, "legacy.internal"), groups).getServerGroup()).isSameAs(ordinaryGroup);
        assertThat(service.create(form(2L, "10.1.2.3"), groups)).isNotNull();
    }
    @Test void forgedHttpUpdateDeleteAndAliasCreateAreRejectedBeforeAuditOrPersistence() throws Exception {
        var audit = mock(AuditLogService.class);
        var controller = new com.portfolio.deployops.controller.ServerApiController(service, groups,
            mock(FileBrowseService.class), audit);
        var mvc = org.springframework.test.web.servlet.setup.MockMvcBuilders.standaloneSetup(controller)
            .setControllerAdvice(new com.portfolio.deployops.controller.GlobalExceptionHandler()).build();
        String body = """
            {"name":"alias","ipAddress":"10.1.2.3","environment":"TEST","serverType":"WAS",
             "serverGroupId":2,"sshPort":22,"deployAccount":"root","authType":"SSH_KEY"}
            """;
        mvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put("/api/v1/servers/7")
            .contentType("application/json").content(body))
            .andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers.status().isForbidden());
        mvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete("/api/v1/servers/7"))
            .andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers.status().isForbidden());
        mvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post("/api/v1/servers")
            .contentType("application/json").content(body))
            .andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers.status().isForbidden());
        verify(servers, never()).save(any());
        verify(servers, never()).delete(any());
        verifyNoInteractions(audit);
    }
}
