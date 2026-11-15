package com.portfolio.deployops.service;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

class SecretMaskingServiceTest {

    private final SecretMaskingService service = new SecretMaskingService();

    @Test
    void masksCommonSecretFormats() {
        String masked = service.mask(String.join("\n",
            "password=DemoAdmin!2026",
            "PRIVATE-TOKEN: glpat-test-token",
            "Authorization: Bearer abcdefghijklmnop",
            "gitlab.token=glpat-hidden",
            "nexus.pass='nexus-secret'",
            "enc:v2:primary:encryptedPayload",
            "ssh -i C:\\Users\\demo-user\\.ssh\\id_rsa deploy@host",
            "docker login https://deploy:secret@registry.example.com",
            "-----BEGIN OPENSSH PRIVATE KEY-----\nprivate-key-body\n-----END OPENSSH PRIVATE KEY-----"
        ));

        assertThat(masked).contains("password=***");
        assertThat(masked).contains("PRIVATE-TOKEN: ***");
        assertThat(masked).contains("Authorization: Bearer ***");
        assertThat(masked).contains("gitlab.token=***");
        assertThat(masked).contains("nexus.pass=***");
        assertThat(masked).contains("enc:v2:***");
        assertThat(masked).contains("-i ***");
        assertThat(masked).contains("https://deploy:***@registry.example.com");
        assertThat(masked).doesNotContain("DemoAdmin!2026");
        assertThat(masked).doesNotContain("glpat-test-token");
        assertThat(masked).doesNotContain("abcdefghijklmnop");
        assertThat(masked).doesNotContain("nexus-secret");
        assertThat(masked).doesNotContain("id_rsa");
        assertThat(masked).doesNotContain("private-key-body");
    }
}
