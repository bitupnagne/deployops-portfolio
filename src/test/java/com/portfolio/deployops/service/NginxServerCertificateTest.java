package com.portfolio.deployops.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

import com.portfolio.deployops.domain.NginxChangeStatus;
import com.portfolio.deployops.domain.Server;
import com.portfolio.deployops.domain.ServerType;
import com.portfolio.deployops.dto.NginxPolicyForm;
import com.portfolio.deployops.repository.NginxChangeRequestRepository;
import com.portfolio.deployops.repository.ServerRepository;
import java.io.ByteArrayInputStream;
import java.security.cert.CertificateFactory;
import java.security.cert.X509Certificate;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Optional;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.web.server.ResponseStatusException;

class NginxServerCertificateTest {
    @TempDir Path temp;
    private final NginxChangeRequestRepository requests = mock(NginxChangeRequestRepository.class);
    private final ServerRepository servers = mock(ServerRepository.class);
    private final SshCommandService ssh = mock(SshCommandService.class);
    private final NginxChangeService service = new NginxChangeService(requests, servers, ssh,
        mock(com.portfolio.deployops.repository.ExecutionGuardRepository.class));
    private static final String CA = """
        -----BEGIN CERTIFICATE-----
        MIIDJTCCAg2gAwIBAgIUDvqBY8DQy66/qXY9iPqJVtdZORAwDQYJKoZIhvcNAQEL
        BQAwFDESMBAGA1UEAwwJbG9jYWxob3N0MB4XDTI2MTAwNzA2NDE0MloXDTI2MTAy
        MTA2NDE0MlowFDESMBAGA1UEAwwJbG9jYWxob3N0MIIBIjANBgkqhkiG9w0BAQEF
        AAOCAQ8AMIIBCgKCAQEAzdSjiOAxzxwa5jPteyFrq4qPauwib0Zra7PjaiX6ykr2
        l+4ZmJYLO0d4Cb9bDgkL2eaIySAiwFP2KY21fjAIDwipfoN1BMSSRGkUvkkC87AW
        ubj7qZOTT/9aV/QaFJoRavJH9nVzqWJKVWmoks8/S1FbqnjhPwrC0zXVA40fjvSI
        MihoBNKqrCd7W9DEr43gqkeVRAlqufX9XfFyniNniUFDl2+TOx9k9vFxEMt19Yf6
        llvEBpgNxsjiuXZ/64VAt6d3jjTMeare0l5ISmginZElm+/vhH/0Y2yCwkmndj/+
        r2PQQqnyxaufy/7IzqphvAsfMq7HlgGS8chblmL2oQIDAQABo28wbTAdBgNVHQ4E
        FgQU08qtFPgfzePkPyoeETaYkB5AoGwwHwYDVR0jBBgwFoAU08qtFPgfzePkPyoe
        ETaYkB5AoGwwDwYDVR0TAQH/BAUwAwEB/zAaBgNVHREEEzARgglsb2NhbGhvc3SH
        BH8AAAEwDQYJKoZIhvcNAQELBQADggEBAJZQGMOZitn/sdQ82/T9K9Ie4X7UU8e6
        Q/JN3VxA/oHWEaTOM6HXCDwlYLqjsl65W0qY/k/4VznzD+30LMEMM0mlqZ3x8pYW
        Ty6kFnznCMGwj0Ud8PhqR8YL7zbIve8JYJ+xhpC46cSUmFsOnpAWjAYA4kGDj/8N
        AqkYD9/nJ0ADVz9h8cpt0FXCh2ayHRR84vRFYUFCdTDioPk2pK3tuKIb+RsZ5t04
        CXrkbJ93i2FYNuKFlIA/46/+0nKQwNsI16/N6erySLMQpeQF1CtuO81uUUvJ5HZS
        8uajqlGZ8dOnQWbjx//14rj9eVGT9+XnL5HOwS5ft5QIhDecYg+auXI=
        -----END CERTIFICATE-----
        """;

    private static final String LEAF = """
        -----BEGIN CERTIFICATE-----
        MIIDQzCCAiugAwIBAgIGICYQFAABMA0GCSqGSIb3DQEBCwUAMBQxEjAQBgNVBAMM
        CWxvY2FsaG9zdDAeFw0yNjEwMTMyMjE5MDFaFw0yNjEwMjAyMjE5MDFaMBwxGjAY
        BgNVBAMMEWxvY2FsaG9zdC1sZWFmLXFhMIIBIjANBgkqhkiG9w0BAQEFAAOCAQ8A
        MIIBCgKCAQEAzdSjiOAxzxwa5jPteyFrq4qPauwib0Zra7PjaiX6ykr2l+4ZmJYL
        O0d4Cb9bDgkL2eaIySAiwFP2KY21fjAIDwipfoN1BMSSRGkUvkkC87AWubj7qZOT
        T/9aV/QaFJoRavJH9nVzqWJKVWmoks8/S1FbqnjhPwrC0zXVA40fjvSIMihoBNKq
        rCd7W9DEr43gqkeVRAlqufX9XfFyniNniUFDl2+TOx9k9vFxEMt19Yf6llvEBpgN
        xsjiuXZ/64VAt6d3jjTMeare0l5ISmginZElm+/vhH/0Y2yCwkmndj/+r2PQQqny
        xaufy/7IzqphvAsfMq7HlgGS8chblmL2oQIDAQABo4GSMIGPMAwGA1UdEwEB/wQC
        MAAwDgYDVR0PAQH/BAQDAgWgMBMGA1UdJQQMMAoGCCsGAQUFBwMBMBoGA1UdEQQT
        MBGCCWxvY2FsaG9zdIcEfwAAATAdBgNVHQ4EFgQU08qtFPgfzePkPyoeETaYkB5A
        oGwwHwYDVR0jBBgwFoAU08qtFPgfzePkPyoeETaYkB5AoGwwDQYJKoZIhvcNAQEL
        BQADggEBABbGcAdvsxvRRdaAPSQS5ACSW1eH/wOmFz+umwdLC1t7/sVGZXCdmjv6
        0o9N9lmUyPLhqKBQlynvChRlR/8lI642geNsMlk37W2vCuldcoLHAslh1/POD92h
        nLX5SR/Mprk1UBcyvtJ/1BcIq0KT4bX/ADjWv89gSkOohRP8XxbSgIq3LY+MOj0X
        m65r8hMBfQuI4aRy4vxm85/ZKJXyBWsFOj16W9YGEDvGS09NcWkiY3qQAesu0Kdm
        +qGNZ/hYTYnrVlm8K/8zbg4FuB2lD0Zp2qsyEcp2QDJ8YoimmSTNP+ytxyCZDNKA
        CL3H53UyiKjXGj1O21rUOyfCvkzGe3M=
        -----END CERTIFICATE-----
        """;

    @Test
    void genuineServerLeafIsAcceptedAndUploadMetadataPreserved() throws Exception {
        prepare();
        assertLeafSignatureAndConstraints();
        assertStoredServerCertificate(LEAF);
    }

    @Test
    void serverLeafFirstWithCaChainIsAcceptedWithoutRejectingChainCa() throws Exception {
        prepare();
        assertLeafSignatureAndConstraints();
        assertStoredServerCertificate(LEAF + CA);
    }

    @Test
    void caFirstWithValidLeafLaterIsRejectedBeforeStorage() throws Exception {
        prepare();
        assertThatThrownBy(() -> service.create(form(), pem(CA + LEAF), key(), 10L))
            .isInstanceOf(ResponseStatusException.class)
            .hasMessageContaining("CA 인증서를 서버 인증서로 사용할 수 없습니다");
        assertNothingStored();
    }

    private void assertLeafSignatureAndConstraints() throws Exception {
        CertificateFactory factory = CertificateFactory.getInstance("X.509");
        X509Certificate leaf = (X509Certificate) factory.generateCertificate(
            new ByteArrayInputStream(LEAF.getBytes(StandardCharsets.US_ASCII)));
        X509Certificate ca = (X509Certificate) factory.generateCertificate(
            new ByteArrayInputStream(CA.getBytes(StandardCharsets.US_ASCII)));
        assertThat(leaf.getBasicConstraints()).isEqualTo(-1);
        assertThat(ca.getBasicConstraints()).isGreaterThanOrEqualTo(0);
        leaf.verify(ca.getPublicKey());
    }

    private void assertStoredServerCertificate(String certificate) throws Exception {
        var created = service.create(form(), pem(certificate), key(), 10L);
        verify(requests).insert(created);
        assertThat(created.getStatus()).isEqualTo(NginxChangeStatus.PENDING);
        assertThat(created.getCertificateName()).isEqualTo("qa-public-leaf");
        assertThat(created.getCertificateFileName()).isEqualTo("server-chain.pem");
        assertThat(created.getPrivateKeyFileName()).isEqualTo("server-key.pem");
        assertThat(created.getTlsProtocols()).isEqualTo("TLSv1.2 TLSv1.3");
        assertThat(created.getStorageKey()).isNotBlank();
        Path stored = temp.resolve("uploads").resolve(created.getStorageKey());
        assertThat(Files.readString(stored.resolve("certificate.pem"), StandardCharsets.US_ASCII))
            .isEqualTo(certificate);
        assertThat(Files.readAllBytes(stored.resolve("private-key.pem"))).isEqualTo(key().getBytes());
        verifyNoInteractions(ssh);
    }

    @Test
    void caCertificateFirstIsRejectedBeforeCreatingFilesOrRequest() throws Exception {
        prepare();
        assertThatThrownBy(() -> service.create(form(), pem(CA), key(), 10L))
            .isInstanceOf(ResponseStatusException.class)
            .hasMessageContaining("CA 인증서를 서버 인증서로 사용할 수 없습니다");
        assertNothingStored();
    }

    @Test
    void anotherCaInChainDoesNotMakeCaFirstAValidServerCertificate() throws Exception {
        prepare();
        assertThatThrownBy(() -> service.create(form(), pem(CA + CA), key(), 10L))
            .isInstanceOf(ResponseStatusException.class)
            .hasMessageContaining("서버 인증서를 PEM 파일의 첫 항목");
        assertNothingStored();
    }

    private void prepare() {
        ReflectionTestUtils.setField(service, "uploadDir", temp.resolve("uploads").toString());
        when(servers.findById(1L)).thenReturn(Optional.of(Server.builder().id(1L)
            .serverType(ServerType.WEB).name("web").agentBindingId("web-binding").build()));
    }

    private void assertNothingStored() throws Exception {
        verify(requests, never()).insert(any());
        verifyNoInteractions(ssh);
        assertThat(Files.exists(temp.resolve("uploads"))).isFalse();
        try (var files = Files.list(temp)) { assertThat(files.count()).isZero(); }
    }

    private MockMultipartFile pem(String value) {
        return new MockMultipartFile("certificate", "server-chain.pem", "application/x-pem-file",
            value.getBytes(StandardCharsets.US_ASCII));
    }

    private MockMultipartFile key() {
        // Format-only marker: no real private key or key-pair validation is claimed by this service unit test.
        return new MockMultipartFile("privateKey", "server-key.pem", "application/x-pem-file",
            "-----BEGIN PRIVATE KEY-----\ntest-only-rejected-input\n-----END PRIVATE KEY-----".getBytes(StandardCharsets.US_ASCII));
    }

    private NginxPolicyForm form() {
        NginxPolicyForm f = new NginxPolicyForm();
        f.setServerId(1L); f.setPolicyName("qa-tls"); f.setServerName("localhost");
        f.setListenPort(443); f.setUpstreamHost("198.18.8.11"); f.setUpstreamPort(9090);
        f.setTlsEnabled(true); f.setTls12(true); f.setTls13(true); f.setCertificateName("qa-public-leaf");
        return f;
    }
}
