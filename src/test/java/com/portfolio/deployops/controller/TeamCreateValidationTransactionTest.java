package com.portfolio.deployops.controller;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

import com.portfolio.deployops.domain.AuditResult;
import com.portfolio.deployops.domain.Team;
import com.portfolio.deployops.repository.TeamRepository;
import com.portfolio.deployops.repository.UserRepository;
import com.portfolio.deployops.service.AuditLogService;
import com.portfolio.deployops.service.TeamService;
import java.sql.Connection;
import javax.sql.DataSource;
import org.junit.jupiter.api.Test;
import org.springframework.aop.framework.ProxyFactory;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.transaction.annotation.AnnotationTransactionAttributeSource;
import org.springframework.transaction.interceptor.TransactionInterceptor;

class TeamCreateValidationTransactionTest {
    @Test
    void duplicateReturns400RollsBackAndNextUniqueCreateCommits() throws Exception {
        Fixture fixture = fixture();
        fixture.mvc().perform(post("/api/v1/teams").contentType("application/json")
                .content("{\"name\":\"existing\",\"memberIds\":[]}"))
            .andExpect(status().isBadRequest())
            .andExpect(jsonPath("$.success").value(false))
            .andExpect(jsonPath("$.message").value("이미 존재하는 팀 이름입니다."));
        verify(fixture.connection()).rollback();
        verify(fixture.connection(), never()).commit();
        verify(fixture.teams(), never()).save(any());
        verifyNoInteractions(fixture.users(), fixture.audit());

        fixture.mvc().perform(post("/api/v1/teams").contentType("application/json")
                .content("{\"name\":\"unique\",\"memberIds\":[]}"))
            .andExpect(status().isCreated())
            .andExpect(jsonPath("$.success").value(true))
            .andExpect(jsonPath("$.data.id").value(9));
        verify(fixture.connection()).commit();
        verify(fixture.connection(), times(1)).rollback();
        verify(fixture.audit()).recordCurrentRequired("팀 생성", "Team", "9", "unique", AuditResult.SUCCESS);
    }

    @Test
    void requiredAuditFailureDoesNotCommitCreate() throws Exception {
        Fixture fixture = fixture();
        doThrow(new IllegalStateException("audit unavailable")).when(fixture.audit())
            .recordCurrentRequired(anyString(), anyString(), anyString(), anyString(), any());
        fixture.mvc().perform(post("/api/v1/teams").contentType("application/json")
                .content("{\"name\":\"unique\",\"memberIds\":[]}"))
            .andExpect(status().isInternalServerError())
            .andExpect(jsonPath("$.success").value(false));
        verify(fixture.teams()).save(any());
        verify(fixture.connection()).rollback();
        verify(fixture.connection(), never()).commit();
    }

    private Fixture fixture() throws Exception {
        DataSource dataSource = mock(DataSource.class);
        Connection connection = mock(Connection.class);
        when(dataSource.getConnection()).thenReturn(connection);
        when(connection.getAutoCommit()).thenReturn(true);
        var transactions = new DataSourceTransactionManager(dataSource);
        TeamRepository teams = mock(TeamRepository.class);
        UserRepository users = mock(UserRepository.class);
        AuditLogService audit = mock(AuditLogService.class);
        when(teams.existsByName(anyString())).thenAnswer(call -> "existing".equals(call.getArgument(0)));
        when(teams.save(any())).thenAnswer(call -> {
            Team team = call.getArgument(0);
            team.setId(9L);
            return team;
        });
        TeamService service = proxy(new TeamService(teams, users), transactions);
        TeamApiController controller = proxy(new TeamApiController(service, audit), transactions);
        MockMvc mvc = MockMvcBuilders.standaloneSetup(controller)
            .setControllerAdvice(new GlobalExceptionHandler()).build();
        return new Fixture(mvc, connection, teams, users, audit);
    }

    @SuppressWarnings("unchecked")
    private <T> T proxy(T target, DataSourceTransactionManager transactions) {
        ProxyFactory factory = new ProxyFactory(target);
        factory.setProxyTargetClass(true);
        factory.addAdvice(new TransactionInterceptor(transactions, new AnnotationTransactionAttributeSource()));
        return (T) factory.getProxy();
    }

    private record Fixture(MockMvc mvc, Connection connection, TeamRepository teams,
                           UserRepository users, AuditLogService audit) { }
}
