package com.portfolio.deployops.service;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;
import static org.mockito.ArgumentMatchers.*;
import com.portfolio.deployops.domain.*;
import com.portfolio.deployops.dto.*;
import com.portfolio.deployops.repository.*;
import java.util.*;
import java.time.LocalDateTime;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.web.server.ResponseStatusException;
import java.nio.file.Path;
class NginxOrderedUpstreamTest {
 NginxChangeRequestRepository repo=mock(NginxChangeRequestRepository.class);
 ServerRepository servers=mock(ServerRepository.class);
 SshCommandService ssh=mock(SshCommandService.class);
 NginxChangeService service=new NginxChangeService(repo,servers,ssh,mock(ExecutionGuardRepository.class));
 @TempDir Path dir;
 @BeforeEach void setup(){
  ReflectionTestUtils.setField(service,"uploadDir",dir.toString());
  when(servers.findById(1L)).thenReturn(Optional.of(Server.builder().id(1L).name("web").serverType(ServerType.WEB).controlMode(ServerControlMode.AGENT).agentBindingId("web-registration-1").environment(ServerEnvironment.TEST).build()));
  for(long i=2;i<=4;i++)when(servers.findById(i)).thenReturn(Optional.of(Server.builder().id(i).serverType(ServerType.WAS).environment(ServerEnvironment.TEST).ipAddress("10.0.0."+i).build()));
  doAnswer(a->{((NginxChangeRequest)a.getArgument(0)).setId(7L);return 1;}).when(repo).insert(any());
 }
 NginxUpstreamForm row(long id,int port){var r=new NginxUpstreamForm();r.setServerId(id);r.setPort(port);return r;}
 NginxPolicyForm form(){var f=new NginxPolicyForm();f.setServerId(1L);f.setPolicyName("ordered");f.setServerName("localhost");f.setListenPort(18091);f.setUpstreamListPresent(true);f.setUpstreams(List.of(row(2,9090),row(3,9091),row(4,9092)));return f;}
 @Test void freezesRegisteredOrderedThreeAndProjectsLegacyFirstTwo(){
  var request=service.create(form(),null,null,10L);
  assertThat(request.getEffectiveUpstreams()).extracting(NginxUpstream::getHost).containsExactly("10.0.0.2","10.0.0.3","10.0.0.4");
  assertThat(request.getUpstreamHost()).isEqualTo("10.0.0.2");assertThat(request.getSecondaryUpstreamHost()).isEqualTo("10.0.0.3");verify(repo,times(3)).insertUpstream(eq(7L),any());
  when(servers.findById(2L)).thenReturn(Optional.of(Server.builder().id(2L).ipAddress("changed").build()));
  assertThat(request.getEffectiveUpstreams().getFirst().getHost()).isEqualTo("10.0.0.2");
 }
 @Test void explicitEmptyRejectedBeforeInsertOrUploads(){var f=form();f.setUpstreams(List.of());assertThatThrownBy(()->service.create(f,null,null,10L)).isInstanceOf(ResponseStatusException.class);verify(repo,never()).insert(any());assertThat(dir.toFile().list()).isEmpty();}
 @Test void rejectsCrossEnvironmentAndMissingEnvironmentOnlyForNewLists(){
  when(servers.findById(2L)).thenReturn(Optional.of(Server.builder().id(2L).serverType(ServerType.WAS).environment(ServerEnvironment.PROD).ipAddress("10.0.0.2").build()));
  assertThatThrownBy(()->service.create(form(),null,null,10L)).hasMessageContaining("같은 환경");
  when(servers.findById(2L)).thenReturn(Optional.of(Server.builder().id(2L).serverType(ServerType.WAS).ipAddress("10.0.0.2").build()));
  assertThatThrownBy(()->service.create(form(),null,null,10L)).hasMessageContaining("같은 환경");verify(repo,never()).insert(any());
 }
 @Test void rejectsExplicitLegacyPortEvenWithoutHost(){var f=form();f.setUpstreamPort(8080);assertThatThrownBy(()->service.create(f,null,null,10L)).hasMessageContaining("함께");verify(repo,never()).insert(any());}
 @Test void rejectsMixedLegacyEvenWhenListValid(){var f=form();f.setUpstreamHost("10.0.0.2");assertThatThrownBy(()->service.create(f,null,null,10L)).hasMessageContaining("함께");verify(repo,never()).insert(any());}
 @Test void rejectsDuplicateNormalizedEndpoint(){var f=form();f.setUpstreams(List.of(row(2,9090),row(2,9090)));assertThatThrownBy(()->service.create(f,null,null,10L)).hasMessageContaining("중복");verify(repo,never()).insert(any());}
 @Test void rejectsNonWasUnknownPortAndSize(){for(var rows:List.of(List.of(row(1,9090)),List.of(row(99,9090)),List.of(row(2,0)),Collections.nCopies(33,row(2,9090)))){var f=form();f.setUpstreams(rows);assertThatThrownBy(()->service.create(f,null,null,10L)).isInstanceOf(ResponseStatusException.class);}verify(repo,never()).insert(any());}
 @Test void staticApiPreservesPurposeAndSecondaryCompatibility(){var f=form();f.setPolicyType("STATIC_SPA");f.setWebRoot("/var/www/qa");f.setApiProxyEnabled(true);f.setApiPrefix("/api/");var q=service.create(f,null,null,10L);assertThat(q.getEffectiveUpstreams()).extracting(NginxUpstream::getPurpose).containsOnly("API");assertThat(q.getApiUpstreamHost()).isEqualTo("10.0.0.2");assertThat(q.getSecondaryUpstreamHost()).isEqualTo("10.0.0.3");}
 @Test void staticWithoutApiRejectsEndpoints(){var f=form();f.setPolicyType("STATIC_FILES");f.setWebRoot("/var/www/qa");assertThatThrownBy(()->service.create(f,null,null,10L)).hasMessageContaining("정적 전용");}
 @Test void legacyOneAndTwoPayloadsKeepFallbackAndDoNotNeedCapability(){var f=form();f.setUpstreamListPresent(false);f.setUpstreams(null);f.setUpstreamHost("10.0.0.2");f.setSecondaryUpstreamEnabled(true);f.setSecondaryUpstreamHost("10.0.0.3");f.setSecondaryUpstreamPort(9091);var q=service.create(f,null,null,10L);assertThat(q.getUpstreamSnapshots()).isEmpty();assertThat(q.getEffectiveUpstreams()).hasSize(2);verify(repo,never()).insertUpstream(any(),any());}
 RemoteCommandResult result(String text){var now=LocalDateTime.now();return new RemoteCommandResult(0,text,"","test",now,now,1);}
 @Test void oldAgentCapabilityFailsBeforeCertificateUpload(){var q=service.create(form(),null,null,10L);q.setStorageKey("qa");when(ssh.execute(any(),any())).thenReturn(result("NGINX_VERSION=old"));assertThatThrownBy(()->ReflectionTestUtils.invokeMethod(service,"apply",q)).isInstanceOf(RemoteExecutionException.class).hasMessageContaining("업데이트");verify(ssh,never()).copyFile(any(),any(),any());verify(ssh,times(1)).execute(any(),any());}
 @Test void approvalArgsUseFrozenAllThreeAndNoLegacyFlags(){var q=service.create(form(),null,null,10L);when(ssh.execute(any(),any())).thenReturn(result("UPSTREAM_LIST_VERSION=1"));ReflectionTestUtils.invokeMethod(service,"apply",q);@SuppressWarnings("unchecked") org.mockito.ArgumentCaptor<List<String>> cap=org.mockito.ArgumentCaptor.forClass(List.class);verify(ssh,times(2)).execute(any(),cap.capture());var args=cap.getAllValues().getLast();assertThat(args).containsSubsequence("--upstream-endpoint","10.0.0.2","9090","--upstream-endpoint","10.0.0.3","9091","--upstream-endpoint","10.0.0.4","9092").doesNotContain("--upstream-host","--secondary-upstream-host","--api-upstream-host");}
 @Test void normalizesIpv6WithoutDnsAndRejectsDirectiveInjection(){assertThat(NginxChangeService.normalizedUpstreamHost("[::1]")).isEqualTo("0:0:0:0:0:0:0:1");assertThatThrownBy(()->NginxChangeService.normalizedUpstreamHost("evil;server")).isInstanceOf(ResponseStatusException.class);}

 @Test void staticApiAgentArgumentsUseAllSnapshotRowsAndOnlyApiPurpose(){
  var f=form();f.setPolicyType("STATIC_SPA");f.setWebRoot("/var/www/qa");f.setApiProxyEnabled(true);f.setApiPrefix("/api/");var q=service.create(f,null,null,10L);when(ssh.execute(any(),any())).thenReturn(result("UPSTREAM_LIST_VERSION=1"));ReflectionTestUtils.invokeMethod(service,"apply",q);
  @SuppressWarnings("unchecked") org.mockito.ArgumentCaptor<List<String>> cap=org.mockito.ArgumentCaptor.forClass(List.class);verify(ssh,times(2)).execute(any(),cap.capture());assertThat(cap.getAllValues().getLast()).containsSubsequence("--upstream-purpose","API").doesNotContain("--api-upstream-host","--secondary-upstream-host");
 }

 @Test void malformedPersistedThirdEndpointFailsBeforeAnySshOrKeyUpload(){var q=service.create(form(),null,null,10L);q.getUpstreamSnapshots().get(2).setPosition(9);assertThatThrownBy(()->ReflectionTestUtils.invokeMethod(service,"apply",q)).isInstanceOf(RemoteExecutionException.class);verifyNoInteractions(ssh);}
}
