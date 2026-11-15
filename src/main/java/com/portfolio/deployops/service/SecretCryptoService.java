package com.portfolio.deployops.service;

import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.util.Arrays;
import java.util.Base64;
import java.util.HashMap;
import java.util.Map;
import javax.crypto.Cipher;
import javax.crypto.spec.GCMParameterSpec;
import javax.crypto.spec.SecretKeySpec;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

@Service
public class SecretCryptoService {

    private static final String PREFIX_V1 = "enc:v1:";
    private static final String PREFIX_V2 = "enc:v2:";
    private static final String DEFAULT_KEY_ID = "primary";
    private static final int IV_LENGTH = 12;
    private static final int TAG_LENGTH_BITS = 128;

    private final SecureRandom secureRandom = new SecureRandom();
    private final String secretKey;
    private final String keyId;
    private final Map<String, String> previousKeys;

    @Autowired
    public SecretCryptoService(
        @Value("${app.crypto.secret-key:${SECRET_ENCRYPTION_KEY:}}") String secretKey,
        @Value("${app.crypto.key-id:${SECRET_ENCRYPTION_KEY_ID:primary}}") String keyId,
        @Value("${app.crypto.previous-keys:${SECRET_PREVIOUS_KEYS:}}") String previousKeys
    ) {
        this.secretKey = secretKey == null ? "" : secretKey.trim();
        this.keyId = normalizeKeyId(keyId);
        this.previousKeys = parsePreviousKeys(previousKeys);
    }

    SecretCryptoService(String secretKey) {
        this(secretKey, DEFAULT_KEY_ID, "");
    }

    public String encryptIfPlain(String value) {
        if (value == null || value.isBlank() || isEncrypted(value)) {
            return value;
        }
        requireKey(secretKey, "민감 정보를 저장하려면 SECRET_ENCRYPTION_KEY 또는 app.crypto.secret-key가 필요합니다.");
        try {
            byte[] iv = new byte[IV_LENGTH];
            secureRandom.nextBytes(iv);

            Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
            cipher.init(Cipher.ENCRYPT_MODE, keySpec(secretKey), new GCMParameterSpec(TAG_LENGTH_BITS, iv));
            byte[] cipherText = cipher.doFinal(value.getBytes(StandardCharsets.UTF_8));

            ByteBuffer buffer = ByteBuffer.allocate(iv.length + cipherText.length);
            buffer.put(iv);
            buffer.put(cipherText);
            return PREFIX_V2 + keyId + ":" + Base64.getEncoder().encodeToString(buffer.array());
        } catch (Exception e) {
            throw new IllegalStateException("민감 정보 암호화에 실패했습니다.", e);
        }
    }

    public String decryptIfEncrypted(String value) {
        if (!isEncrypted(value)) {
            return value;
        }
        try {
            String key = keyForEncryptedValue(value);
            requireKey(key, "암호화된 민감 정보를 읽으려면 해당 key-id의 암호화 키가 필요합니다.");
            byte[] payload = Base64.getDecoder().decode(encryptedPayload(value));
            if (payload.length <= IV_LENGTH) {
                throw new IllegalArgumentException("암호문 형식이 올바르지 않습니다.");
            }
            byte[] iv = Arrays.copyOfRange(payload, 0, IV_LENGTH);
            byte[] cipherText = Arrays.copyOfRange(payload, IV_LENGTH, payload.length);

            Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
            cipher.init(Cipher.DECRYPT_MODE, keySpec(key), new GCMParameterSpec(TAG_LENGTH_BITS, iv));
            return new String(cipher.doFinal(cipherText), StandardCharsets.UTF_8);
        } catch (Exception e) {
            throw new IllegalStateException("민감 정보 복호화에 실패했습니다.", e);
        }
    }

    public boolean isEncrypted(String value) {
        return value != null && (value.startsWith(PREFIX_V1) || value.startsWith(PREFIX_V2));
    }

    public boolean isEncryptedWithCurrentKey(String value) {
        return value != null && value.startsWith(PREFIX_V2 + keyId + ":");
    }

    public String reencryptWithCurrentKey(String value) {
        if (value == null || value.isBlank() || isEncryptedWithCurrentKey(value)) {
            return value;
        }
        return encryptIfPlain(decryptIfEncrypted(value));
    }

    public String currentKeyId() {
        return keyId;
    }

    private SecretKeySpec keySpec(String rawKey) throws Exception {
        byte[] digest = MessageDigest.getInstance("SHA-256")
            .digest(rawKey.getBytes(StandardCharsets.UTF_8));
        return new SecretKeySpec(digest, "AES");
    }

    private void requireKey(String key, String message) {
        if (key == null || key.isBlank()) {
            throw new IllegalStateException(message);
        }
    }

    private String keyForEncryptedValue(String value) {
        if (value.startsWith(PREFIX_V1)) {
            return secretKey;
        }
        String encryptedKeyId = encryptedKeyId(value);
        if (keyId.equals(encryptedKeyId)) {
            return secretKey;
        }
        return previousKeys.get(encryptedKeyId);
    }

    private String encryptedPayload(String value) {
        if (value.startsWith(PREFIX_V1)) {
            return value.substring(PREFIX_V1.length());
        }
        int separator = value.indexOf(':', PREFIX_V2.length());
        if (separator < 0 || separator == value.length() - 1) {
            throw new IllegalArgumentException("암호문 형식이 올바르지 않습니다.");
        }
        return value.substring(separator + 1);
    }

    private String encryptedKeyId(String value) {
        int separator = value.indexOf(':', PREFIX_V2.length());
        if (separator < 0) {
            throw new IllegalArgumentException("암호문 key-id 형식이 올바르지 않습니다.");
        }
        return value.substring(PREFIX_V2.length(), separator);
    }

    private String normalizeKeyId(String rawKeyId) {
        String normalized = rawKeyId == null || rawKeyId.isBlank() ? DEFAULT_KEY_ID : rawKeyId.trim();
        if (normalized.contains(":") || normalized.contains("=") || normalized.contains(",")) {
            throw new IllegalArgumentException("암호화 key-id에는 ':', '=', ',' 문자를 사용할 수 없습니다.");
        }
        return normalized;
    }

    private Map<String, String> parsePreviousKeys(String rawPreviousKeys) {
        Map<String, String> keys = new HashMap<>();
        if (rawPreviousKeys == null || rawPreviousKeys.isBlank()) {
            return keys;
        }
        for (String entry : rawPreviousKeys.split("[;,]")) {
            if (entry.isBlank()) {
                continue;
            }
            int separator = entry.indexOf('=');
            if (separator <= 0 || separator == entry.length() - 1) {
                throw new IllegalArgumentException("이전 암호화 키는 key-id=secret 형식으로 입력해야 합니다.");
            }
            String previousKeyId = normalizeKeyId(entry.substring(0, separator));
            keys.put(previousKeyId, entry.substring(separator + 1).trim());
        }
        return keys;
    }
}
