package com.portfolio.deployops.service;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;
import com.portfolio.deployops.domain.*;
import com.portfolio.deployops.dto.*;
import com.portfolio.deployops.repository.*;
import java.util.*;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import org.springframework.http.HttpStatus;
import org.springframework.web.server.ResponseStatusException;

class ServerGroupEnvironmentConsistencyTest {
    final ServerRepository servers=mock(ServerRepository.class);
    final ServerGroupRepository groups=mock(ServerGroupRepository.class);
    final ProjectRepository projects=mock(ProjectRepository.class);
    final ServerService service=new ServerService(servers,mock(RemoteCommandLogRepository.class),mock(ManualServerExecutionRepository.class));
    {
        var agents = mock(AgentQueueService.class);
        service.configureAgentQueue(agents);
        when(agents.status(anyLong())).thenReturn(java.util.Map.of("registrationState", "NOT_REGISTERED"));
    }
    final ServerGroupService groupService=new ServerGroupService(groups,servers,projects);
    ServerForm form(ServerType type,ServerEnvironment env,Long id) {
        var f=new ServerForm();
        f.setName("changed");
        f.setIpAddress("198.18.30.11");
        f.setEnvironment(env);
        f.setServerType(type);
        f.setServerGroupId(id);
        f.setSshPort(2022);
        return f;
    }
    ServerGroup group(ServerEnvironment env) {
        return ServerGroup.builder().id(3L).name("QA").environment(env).rollingIntervalSec(30).description("before").build();
    }
    ServerGroupForm groupForm(ServerEnvironment env) {
        var f=new ServerGroupForm();
        f.setName("changed");
        f.setEnvironment(env);
        f.setDescription("after");
        f.setRollingIntervalSec(0);
        return f;
    }
    List<Object> serverState(Server server) {
        ServerGroup selected = server.getServerGroup();
        return Arrays.asList(
            server.getId(), server.getName(), server.getIpAddress(), server.getEnvironment(),
            server.getServerType(), server.getSshPort(), server.getDeployAccount(), server.getAuthType(),
            server.getDeployPath(), server.getMemo(), server.getOrderIndex(), server.getStatus(),
            server.isAutoStart(), server.getStartupService(),
            selected == null ? null : selected.getId(),
            selected == null ? null : selected.getEnvironment(),
            selected == null ? null : selected.getName());
    }

    List<Object> groupState(ServerGroup group) {
        return Arrays.asList(
            group.getId(), group.getName(), group.getEnvironment(),
            group.getRollingIntervalSec(), group.getDescription(), group.getDisplayOrder());
    }

    void rejected(Runnable action,HttpStatus status) {
        assertThatThrownBy(action::run).isInstanceOfSatisfying(ResponseStatusException.class,e->assertThat(e.getStatusCode()).isEqualTo(status));
        verify(servers,never()).save(any());
        verify(groups,never()).save(any());
    }

    @Test
    void crossEnvCreateRejects() {
        when(groups.findByIdForUpdate(3L)).thenReturn(Optional.of(group(ServerEnvironment.PROD)));
        rejected(()->service.create(form(ServerType.WEB,ServerEnvironment.TEST,3L),groups),HttpStatus.CONFLICT);
    }

    @Test
    void crossEnvUpdateNeverMutatesExisting() {
        var s=Server.builder().id(1L).name("devweb1").environment(ServerEnvironment.TEST).serverType(ServerType.WEB).sshPort(2022).deployAccount("deploy").deployPath("/usr/local/deploy").serverGroup(group(ServerEnvironment.TEST)).build();
        var before=serverState(s);
        when(servers.findByIdForMutation(1L)).thenReturn(Optional.of(s));
        when(groups.findByIdForUpdate(3L)).thenReturn(Optional.of(group(ServerEnvironment.PROD)));
        rejected(()->service.update(1L,form(ServerType.WAS,ServerEnvironment.TEST,3L),groups),HttpStatus.CONFLICT);
        assertThat(serverState(s)).isEqualTo(before);
    }

    @Test
    void missingGroupRejectsCreate() {
        when(groups.findByIdForUpdate(3L)).thenReturn(Optional.empty());
        rejected(()->service.create(form(ServerType.WAS,ServerEnvironment.TEST,3L),groups),HttpStatus.NOT_FOUND);
    }

    @Test
    void missingGroupRejectsUpdateBeforeMutation() {
        var s=Server.builder().id(1L).name("before").build();
        when(servers.findByIdForMutation(1L)).thenReturn(Optional.of(s));
        when(groups.findByIdForUpdate(3L)).thenReturn(Optional.empty());
        rejected(()->service.update(1L,form(ServerType.WAS,ServerEnvironment.TEST,3L),groups),HttpStatus.NOT_FOUND);
        assertThat(s.getName()).isEqualTo("before");
    }

    @Test
    void selectedGroupMissingEnvRejects() {
        rejected(()->service.create(form(ServerType.WAS,null,3L),groups),HttpStatus.BAD_REQUEST);
        verifyNoInteractions(groups);
    }

    @ParameterizedTest
    @EnumSource(ServerType.class)
    void existingTypesAllowMatchingDevGroup(ServerType type) {
        var g=group(ServerEnvironment.DEV);
        when(groups.findByIdForUpdate(3L)).thenReturn(Optional.of(g));
        when(servers.save(any())).thenAnswer(c->c.getArgument(0));
        var saved=service.create(form(type,ServerEnvironment.DEV,3L),groups);
        assertThat(saved.getServerGroup()).isSameAs(g);
        assertThat(saved.getServerType()).isEqualTo(type);
        var order=inOrder(groups,servers);
        order.verify(groups).findByIdForUpdate(3L);
        order.verify(servers).save(saved);
    }

    @ParameterizedTest
    @EnumSource(ServerType.class)
    void ungroupedInfraCreateAllowed(ServerType type) {
        when(servers.save(any())).thenAnswer(c->c.getArgument(0));
        assertThat(service.create(form(type,ServerEnvironment.DEV,null),groups).getServerGroup()).isNull();
        verifyNoInteractions(groups);
    }

    @ParameterizedTest
    @EnumSource(ServerEnvironment.class)
    void matchingUpdateAllowed(ServerEnvironment env) {
        var s=Server.builder().id(1L).build();
        var g=group(env);
        when(servers.findByIdForMutation(1L)).thenReturn(Optional.of(s));
        when(groups.findByIdForUpdate(3L)).thenReturn(Optional.of(g));
        when(servers.save(any())).thenAnswer(c->c.getArgument(0));
        assertThat(service.update(1L,form(ServerType.WAS,env,3L),groups).getServerGroup()).isSameAs(g);
    }

    @Test
    void explicitNullGroupDetachesWithoutGroupLookup() {
        var s=Server.builder().id(1L).serverGroup(group(ServerEnvironment.TEST)).build();
        when(servers.findByIdForMutation(1L)).thenReturn(Optional.of(s));
        when(servers.save(any())).thenAnswer(c->c.getArgument(0));
        assertThat(service.update(1L,form(ServerType.WEB,ServerEnvironment.PROD,null),groups).getServerGroup()).isNull();
        verifyNoInteractions(groups);
    }

    @Test
    void populatedGroupEnvChangeRejectsBeforeMutation() {
        var g=group(ServerEnvironment.TEST);
        var before=groupState(g);
        when(groups.findByIdForUpdate(3L)).thenReturn(Optional.of(g));
        when(servers.findByServerGroupId(3L)).thenReturn(List.of(Server.builder().id(1L).build()));
        rejected(()->groupService.update(3L,groupForm(ServerEnvironment.PROD)),HttpStatus.CONFLICT);
        assertThat(groupState(g)).isEqualTo(before);
    }

    @Test
    void referencedEmptyGroupEnvChangeRejectsBeforeMutation() {
        var g=group(ServerEnvironment.TEST);
        var before=groupState(g);
        when(groups.findByIdForUpdate(3L)).thenReturn(Optional.of(g));
        when(projects.findProjectNamesByServerGroupId(3L)).thenReturn(List.of("service-A"));
        rejected(()->groupService.update(3L,groupForm(ServerEnvironment.PROD)),HttpStatus.CONFLICT);
        assertThat(groupState(g)).isEqualTo(before);
    }

    @Test
    void sameEnvEditsAllowedEvenWhenPopulatedReferenced() {
        var g=group(ServerEnvironment.TEST);
        when(groups.findByIdForUpdate(3L)).thenReturn(Optional.of(g));
        when(groups.save(any())).thenAnswer(c->c.getArgument(0));
        var saved=groupService.update(3L,groupForm(ServerEnvironment.TEST));
        assertThat(saved.getName()).isEqualTo("changed");
        assertThat(saved.getRollingIntervalSec()).isZero();
        assertThat(saved.getDescription()).isEqualTo("after");
        verifyNoInteractions(servers,projects);
    }

    @Test
    void emptyUnreferencedGroupEnvChangeAllowed() {
        var g=group(ServerEnvironment.TEST);
        when(groups.findByIdForUpdate(3L)).thenReturn(Optional.of(g));
        when(groups.save(any())).thenAnswer(c->c.getArgument(0));
        assertThat(groupService.update(3L,groupForm(ServerEnvironment.PROD)).getEnvironment()).isEqualTo(ServerEnvironment.PROD);
    }

    @Test
    void sharedLookupHasLockingSqlAndResultMap()throws Exception {
        var m=ServerGroupRepository.class.getMethod("findByIdForUpdate",Long.class);
        assertThat(String.join(" ",m.getAnnotation(org.apache.ibatis.annotations.Select.class).value())).contains("WHERE id = #{id} FOR UPDATE");
        assertThat(m.getAnnotation(org.apache.ibatis.annotations.ResultMap.class).value()).containsExactly("serverGroupByIdResult");
    }
}
