package com.portfolio.deployops.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import org.junit.jupiter.api.Test;

class SecretCryptoServiceTest {

    @Test
    void encryptsAndDecryptsPlainSecret() {
        SecretCryptoService service = new SecretCryptoService("test-secret-key");

        String encrypted = service.encryptIfPlain("glpat-test-token");

        assertThat(encrypted).startsWith("enc:v2:primary:");
        assertThat(encrypted).doesNotContain("glpat-test-token");
        assertThat(service.decryptIfEncrypted(encrypted)).isEqualTo("glpat-test-token");
    }

    @Test
    void decryptsPreviousKeyAndReencryptsWithCurrentKey() {
        SecretCryptoService oldService = new SecretCryptoService("old-secret-key", "old", "");
        String oldEncrypted = oldService.encryptIfPlain("legacy-secret");
        SecretCryptoService newService = new SecretCryptoService("new-secret-key", "new", "old=old-secret-key");

        String rotated = newService.reencryptWithCurrentKey(oldEncrypted);

        assertThat(newService.decryptIfEncrypted(oldEncrypted)).isEqualTo("legacy-secret");
        assertThat(rotated).startsWith("enc:v2:new:");
        assertThat(newService.decryptIfEncrypted(rotated)).isEqualTo("legacy-secret");
    }

    @Test
    void keepsExistingPlainValueReadable() {
        SecretCryptoService service = new SecretCryptoService("");

        assertThat(service.decryptIfEncrypted("legacy-plain-token")).isEqualTo("legacy-plain-token");
    }

    @Test
    void requiresKeyWhenStoringPlainSecret() {
        SecretCryptoService service = new SecretCryptoService("");

        assertThatThrownBy(() -> service.encryptIfPlain("token"))
            .isInstanceOf(IllegalStateException.class)
            .hasMessageContaining("SECRET_ENCRYPTION_KEY");
    }
}
