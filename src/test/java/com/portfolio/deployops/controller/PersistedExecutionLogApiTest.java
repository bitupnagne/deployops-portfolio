package com.portfolio.deployops.controller;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;
import com.portfolio.deployops.config.SecurityUserDetails;
import com.portfolio.deployops.domain.*;
import com.portfolio.deployops.repository.RemoteCommandLogRepository;
import com.portfolio.deployops.service.*;
import java.util.*;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.web.server.ResponseStatusException;
class PersistedExecutionLogApiTest {
 final DeployRequestService requests=mock(DeployRequestService.class);
 final ProjectPermissionService permissions=mock(ProjectPermissionService.class);
 final RemoteCommandLogRepository logs=mock(RemoteCommandLogRepository.class);
 final SecretMaskingService masking=new SecretMaskingService();
 final PersistedExecutionLogApiController controller=new PersistedExecutionLogApiController(requests,permissions,logs,masking);
 final SecurityUserDetails user=new SecurityUserDetails(User.builder().id(7L).role(UserRole.DEVELOPER).build());
 void request(DeployEnvironment env){when(requests.findById(9L,user)).thenReturn(DeployRequest.builder().id(9L).project(Project.builder().id(3L).build()).environment(env).status(DeployStatus.TEST_FAILED).build());}
 @Test void unrelatedProjectDeniedBeforeLogs(){when(requests.findById(9L,user)).thenThrow(new ResponseStatusException(HttpStatus.FORBIDDEN));assertThatThrownBy(()->controller.read(9L,20,user)).isInstanceOf(ResponseStatusException.class);verifyNoInteractions(logs,permissions);}
 @Test void environmentExecutionPermissionDeniedBeforeLogs(){request(DeployEnvironment.TEST);doThrow(new ResponseStatusException(HttpStatus.FORBIDDEN)).when(permissions).assertPermission(user,3L,ProjectPermissionType.TEST_EXECUTE,DeployEnvironment.TEST);assertThatThrownBy(()->controller.read(9L,20,user)).isInstanceOf(ResponseStatusException.class);verifyNoInteractions(logs);}
 @Test void prodRequiresProdScopedPermissionAndBoundsUpperLimit(){request(DeployEnvironment.PROD);when(logs.findBoundedByRequest(9L,20)).thenReturn(List.of());controller.read(9L,100000,user);verify(permissions).assertPermission(user,3L,ProjectPermissionType.PROD_EXECUTE,DeployEnvironment.PROD);verify(logs).findBoundedByRequest(9L,20);}
 @Test void zeroLimitIsBoundedAndEmptyIsTruthful(){request(DeployEnvironment.TEST);when(logs.findBoundedByRequest(9L,1)).thenReturn(List.of());Map<?,?> body=(Map<?,?>)controller.read(9L,0,user).getData();assertThat(body.get("requestId")).isEqualTo(9L);assertThat((List<?>)body.get("records")).isEmpty();verify(logs).findBoundedByRequest(9L,1);}
 @Test void masksStoredSecretsBoundsStringsAndPreservesChronologicalAttempts(){request(DeployEnvironment.TEST);when(logs.findBoundedByRequest(9L,20)).thenReturn(List.of(RemoteCommandLog.builder().id(2L).serverName("WAS").stdoutText("x".repeat(16385)).build(),RemoteCommandLog.builder().id(1L).serverName("<script>alert(1)</script>").commandDisplay("docker login --password canary-password").stdoutText("TOKEN=canary-token\n<script>literal</script>").stderrText("Authorization: Bearer canary-secret").exitCode(1).build()));Map<?,?> body=(Map<?,?>)controller.read(9L,20,user).getData();List<?> rows=(List<?>)body.get("records");Map<?,?> first=(Map<?,?>)rows.get(0),second=(Map<?,?>)rows.get(1);assertThat(first.get("attemptId")).isEqualTo(1L);assertThat(first.values().toString()).doesNotContain("canary-password","canary-token","canary-secret").contains("***","<script>literal</script>");assertThat(((String)second.get("stdout")).length()).isEqualTo(16384);assertThat(second.get("truncated")).isEqualTo(true);}
 @Test void unknownEnvironmentRefusesBeforePermissionsAndLogs(){request(null);assertThatThrownBy(()->controller.read(9L,20,user)).isInstanceOf(ResponseStatusException.class);verifyNoInteractions(logs,permissions);}
}