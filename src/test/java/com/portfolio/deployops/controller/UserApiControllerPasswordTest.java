package com.portfolio.deployops.controller;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.portfolio.deployops.config.SecurityUserDetails;
import com.portfolio.deployops.domain.AuditResult;
import com.portfolio.deployops.domain.User;
import com.portfolio.deployops.domain.UserRole;
import com.portfolio.deployops.dto.UserForm;
import com.portfolio.deployops.service.AuditLogService;
import com.portfolio.deployops.service.GitlabService;
import com.portfolio.deployops.service.UserService;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.NullAndEmptySource;
import org.junit.jupiter.params.provider.ValueSource;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.http.HttpStatus;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.security.access.prepost.PreAuthorize;
import java.util.Map;
import java.util.Optional;
import java.sql.Connection;
import javax.sql.DataSource;
import com.portfolio.deployops.repository.UserRepository;
import com.portfolio.deployops.repository.TeamRepository;
import com.portfolio.deployops.service.SecretCryptoService;
import org.springframework.aop.framework.ProxyFactory;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.web.method.annotation.AuthenticationPrincipalArgumentResolver;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.transaction.annotation.AnnotationTransactionAttributeSource;
import org.springframework.transaction.interceptor.TransactionInterceptor;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;

@ExtendWith(MockitoExtension.class)
class UserApiControllerPasswordTest {

    @Mock private UserService userService;
    @Mock private GitlabService gitlabService;
    @Mock private AuditLogService auditLogService;
    @InjectMocks private UserApiController controller;

    @ParameterizedTest
    @NullAndEmptySource
    @ValueSource(strings = {"   "})
    void createRejectsMissingInitialPasswordBeforeCallingServices(String password) {
        UserForm form = new UserForm();
        form.setPassword(password);

        var response = controller.create(form);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
        assertThat(response.getBody()).isNotNull();
        assertThat(response.getBody().getErrorCode()).isEqualTo("VALIDATION_ERROR");
        assertThat(response.getBody().getMessage()).isEqualTo("초기 비밀번호를 입력해 주세요.");
        verifyNoInteractions(userService, gitlabService, auditLogService);
    }

    @org.junit.jupiter.api.Test
    void resetRequiresAdminAndRejectsSelf() {
        SecurityUserDetails nonAdmin = principal(1L, UserRole.APPROVER);
        SecurityUserDetails admin = principal(1L, UserRole.SUPER_ADMIN);

        assertThat(controller.resetPassword(2L, Map.of("temporaryPassword", "Unique!Pass9"), nonAdmin)
            .getStatusCode()).isEqualTo(HttpStatus.FORBIDDEN);
        assertThat(controller.resetPassword(1L, Map.of("temporaryPassword", "Unique!Pass9"), admin)
            .getStatusCode()).isEqualTo(HttpStatus.FORBIDDEN);
        verifyNoInteractions(userService, auditLogService);
    }

    @org.junit.jupiter.api.Test
    void resetRequiresValidPasswordAndRecordsRequiredAuditWithoutSecret() throws Exception {
        SecurityUserDetails admin = principal(1L, UserRole.SUPER_ADMIN);
        User target = User.builder().id(2L).loginId("approver").name("승인자")
            .role(UserRole.APPROVER).build();
        when(userService.resetPassword(2L, "Unique!Pass9")).thenReturn(target);

        var response = controller.resetPassword(2L, Map.of("temporaryPassword", "Unique!Pass9"), admin);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(response.getBody().getMessage()).doesNotContain("Unique!Pass9");
        verify(auditLogService).recordCurrentRequired("사용자 비밀번호 재설정", "User", "2",
            "승인자(approver, APPROVER)", AuditResult.SUCCESS);
        assertThat(UserApiController.class.getMethod("resetPassword", Long.class, Map.class,
            SecurityUserDetails.class).getAnnotation(Transactional.class)).isNotNull();
        assertThat(UserApiController.class.getMethod("resetPassword", Long.class, Map.class,
            SecurityUserDetails.class).getAnnotation(PreAuthorize.class).value())
            .isEqualTo("hasRole('SUPER_ADMIN')");
    }

    @org.junit.jupiter.api.Test
    void resetAuditFailurePropagatesForTransactionRollback() {
        SecurityUserDetails admin = principal(1L, UserRole.SUPER_ADMIN);
        User target = User.builder().id(2L).loginId("approver").name("승인자")
            .role(UserRole.APPROVER).build();
        when(userService.resetPassword(2L, "Unique!Pass9")).thenReturn(target);
        org.mockito.Mockito.doThrow(new IllegalStateException("audit unavailable"))
            .when(auditLogService).recordCurrentRequired(org.mockito.ArgumentMatchers.anyString(),
                org.mockito.ArgumentMatchers.anyString(), org.mockito.ArgumentMatchers.anyString(),
                org.mockito.ArgumentMatchers.anyString(), org.mockito.ArgumentMatchers.any());

        assertThatThrownBy(() -> controller.resetPassword(2L,
            Map.of("temporaryPassword", "Unique!Pass9"), admin))
            .isInstanceOf(IllegalStateException.class);
    }

    @org.junit.jupiter.api.Test
    void repeatedTemporaryPasswordReturns400AfterJoinedTransactionRollback() throws Exception {
        PasswordResetFixture fixture = passwordResetFixture();
        SecurityContextHolder.getContext().setAuthentication(
            new UsernamePasswordAuthenticationToken(principal(1L, UserRole.SUPER_ADMIN), null));
        try {
            fixture.mvc().perform(post("/api/v1/users/2/reset-password")
                    .contentType("application/json").content("{\"temporaryPassword\":\"Unique!Pass9\"}"))
                .andExpect(status().isOk());
            fixture.mvc().perform(post("/api/v1/users/2/reset-password")
                    .contentType("application/json").content("{\"temporaryPassword\":\"Unique!Pass9\"}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.success").value(false))
                .andExpect(jsonPath("$.message").value("기존 비밀번호와 다른 임시 비밀번호를 입력해 주세요."));

            verify(fixture.connection()).commit();
            verify(fixture.connection()).rollback();
            verify(fixture.repository(), times(1)).updatePassword(org.mockito.ArgumentMatchers.any());
            verify(auditLogService, times(1)).recordCurrentRequired("사용자 비밀번호 재설정", "User", "2",
                "승인자(approver, APPROVER)", AuditResult.SUCCESS);
        } finally {
            SecurityContextHolder.clearContext();
        }
    }

    @org.junit.jupiter.api.Test
    void requiredAuditFailureRollsBackPasswordResetTransactionAndReturns500() throws Exception {
        PasswordResetFixture fixture = passwordResetFixture();
        org.mockito.Mockito.doThrow(new IllegalStateException("audit unavailable"))
            .when(auditLogService).recordCurrentRequired(org.mockito.ArgumentMatchers.anyString(),
                org.mockito.ArgumentMatchers.anyString(), org.mockito.ArgumentMatchers.anyString(),
                org.mockito.ArgumentMatchers.anyString(), org.mockito.ArgumentMatchers.any());
        SecurityContextHolder.getContext().setAuthentication(
            new UsernamePasswordAuthenticationToken(principal(1L, UserRole.SUPER_ADMIN), null));
        try {
            fixture.mvc().perform(post("/api/v1/users/2/reset-password")
                    .contentType("application/json").content("{\"temporaryPassword\":\"Unique!Pass9\"}"))
                .andExpect(status().isInternalServerError())
                .andExpect(jsonPath("$.success").value(false));
            verify(fixture.connection()).rollback();
            verify(fixture.connection(), never()).commit();
        } finally {
            SecurityContextHolder.clearContext();
        }
    }

    @org.junit.jupiter.api.Test
    void duplicateCreateReturns400AfterJoinedRollbackAndNextUniqueCreateCommits() throws Exception {
        UserCreateFixture fixture = userCreateFixture();
        fixture.mvc().perform(post("/api/v1/users")
                .contentType("application/json").content(createBody("existing")))
            .andExpect(status().isBadRequest())
            .andExpect(jsonPath("$.success").value(false))
            .andExpect(jsonPath("$.message").value("이미 사용 중인 아이디입니다."));
        verify(fixture.connection()).rollback();
        verify(fixture.connection(), never()).commit();
        verify(fixture.repository(), never()).save(org.mockito.ArgumentMatchers.any());
        verifyNoInteractions(auditLogService);

        fixture.mvc().perform(post("/api/v1/users")
                .contentType("application/json").content(createBody("unique")))
            .andExpect(status().isCreated())
            .andExpect(jsonPath("$.success").value(true))
            .andExpect(jsonPath("$.data.id").value(9));
        verify(fixture.connection()).commit();
        verify(fixture.connection(), times(1)).rollback();
        var saved = org.mockito.ArgumentCaptor.forClass(User.class);
        verify(fixture.repository()).save(saved.capture());
        assertThat(saved.getValue().getLoginId()).isEqualTo("unique");
        assertThat(saved.getValue().isForcePasswordChange()).isTrue();
        verify(auditLogService).recordCurrentRequired("사용자 등록", "User", "9",
            "검증 사용자(unique, DEVELOPER)", AuditResult.SUCCESS);
    }

    @org.junit.jupiter.api.Test
    void requiredAuditFailureRollsBackJoinedCreateAndReturns500() throws Exception {
        UserCreateFixture fixture = userCreateFixture();
        org.mockito.Mockito.doThrow(new IllegalStateException("audit unavailable"))
            .when(auditLogService).recordCurrentRequired(org.mockito.ArgumentMatchers.anyString(),
                org.mockito.ArgumentMatchers.anyString(), org.mockito.ArgumentMatchers.anyString(),
                org.mockito.ArgumentMatchers.anyString(), org.mockito.ArgumentMatchers.any());
        fixture.mvc().perform(post("/api/v1/users")
                .contentType("application/json").content(createBody("unique")))
            .andExpect(status().isInternalServerError())
            .andExpect(jsonPath("$.success").value(false));
        verify(fixture.connection()).rollback();
        verify(fixture.connection(), never()).commit();
        verify(fixture.repository()).save(org.mockito.ArgumentMatchers.any());
    }

    @org.junit.jupiter.api.Test
    void weakCreateReturns400RollsBackAndCannotPersistOrAuditSuccess() throws Exception {
        UserCreateFixture fixture = userCreateFixture(false);
        fixture.mvc().perform(post("/api/v1/users")
                .contentType("application/json").content(createBody("weak").replace("Unique!Pass9", "x")))
            .andExpect(status().isBadRequest())
            .andExpect(jsonPath("$.success").value(false))
            .andExpect(jsonPath("$.message").value("비밀번호는 9자 이상이어야 합니다."));
        verify(fixture.connection()).rollback();
        verify(fixture.connection(), never()).commit();
        verify(fixture.repository(), never()).save(org.mockito.ArgumentMatchers.any());
        verifyNoInteractions(auditLogService);
    }

    private String createBody(String login) {
        return "{\"loginId\":\"" + login + "\",\"password\":\"Unique!Pass9\",\"name\":\"검증 사용자\",\"role\":\"DEVELOPER\",\"active\":true}";
    }

    private UserCreateFixture userCreateFixture() throws Exception {
        return userCreateFixture(true);
    }

    private UserCreateFixture userCreateFixture(boolean reachesRepository) throws Exception {
        DataSource dataSource = mock(DataSource.class);
        Connection connection = mock(Connection.class);
        when(dataSource.getConnection()).thenReturn(connection);
        when(connection.getAutoCommit()).thenReturn(true);
        var transactions = new DataSourceTransactionManager(dataSource);
        UserRepository repository = mock(UserRepository.class);
        if (reachesRepository) {
            when(repository.existsByLoginId(org.mockito.ArgumentMatchers.anyString()))
                .thenAnswer(invocation -> "existing".equals(invocation.getArgument(0)));
            when(repository.save(org.mockito.ArgumentMatchers.any())).thenAnswer(invocation -> {
                User user = invocation.getArgument(0); user.setId(9L); return user;
            });
        }
        UserService service = transactionalProxy(new UserService(repository, new BCryptPasswordEncoder(4),
            mock(TeamRepository.class), mock(SecretCryptoService.class), auditLogService), transactions);
        UserApiController transactionalController = transactionalProxy(
            new UserApiController(service, gitlabService, auditLogService), transactions);
        MockMvc mvc = MockMvcBuilders.standaloneSetup(transactionalController)
            .setControllerAdvice(new GlobalExceptionHandler()).build();
        return new UserCreateFixture(mvc, connection, repository);
    }

    private record UserCreateFixture(MockMvc mvc, Connection connection, UserRepository repository) {}

    private PasswordResetFixture passwordResetFixture() throws Exception {
        DataSource dataSource = mock(DataSource.class);
        Connection connection = mock(Connection.class);
        when(dataSource.getConnection()).thenReturn(connection);
        when(connection.getAutoCommit()).thenReturn(true);
        var transactions = new DataSourceTransactionManager(dataSource);
        UserRepository repository = mock(UserRepository.class);
        BCryptPasswordEncoder encoder = new BCryptPasswordEncoder(4);
        User target = User.builder().id(2L).loginId("approver").name("승인자")
            .role(UserRole.APPROVER).password(encoder.encode("Previous!Pass8")).build();
        when(repository.findByIdForUpdate(2L)).thenReturn(Optional.of(target));
        when(repository.updatePassword(target)).thenReturn(1);
        UserService service = transactionalProxy(new UserService(repository, encoder,
            mock(TeamRepository.class), mock(SecretCryptoService.class), auditLogService), transactions);
        UserApiController transactionalController = transactionalProxy(
            new UserApiController(service, gitlabService, auditLogService), transactions);
        MockMvc mvc = MockMvcBuilders.standaloneSetup(transactionalController)
            .setCustomArgumentResolvers(new AuthenticationPrincipalArgumentResolver())
            .setControllerAdvice(new GlobalExceptionHandler()).build();
        return new PasswordResetFixture(mvc, connection, repository);
    }

    @SuppressWarnings("unchecked")
    private <T> T transactionalProxy(T target, DataSourceTransactionManager transactions) {
        ProxyFactory factory = new ProxyFactory(target);
        factory.setProxyTargetClass(true);
        factory.addAdvice(new TransactionInterceptor(transactions, new AnnotationTransactionAttributeSource()));
        return (T) factory.getProxy();
    }

    private record PasswordResetFixture(MockMvc mvc, Connection connection, UserRepository repository) {}

    private SecurityUserDetails principal(Long id, UserRole role) {
        return new SecurityUserDetails(User.builder().id(id).loginId("admin")
            .role(role).active(true).build());
    }
}
