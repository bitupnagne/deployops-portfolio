package com.portfolio.deployops.service;

import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.springframework.stereotype.Service;

@Service
public class SecretMaskingService {

    private static final String MASK = "***";

    private static final Pattern PRIVATE_KEY_BLOCK = Pattern.compile(
        "-----BEGIN [^-]*PRIVATE KEY-----[\\s\\S]*?-----END [^-]*PRIVATE KEY-----",
        Pattern.CASE_INSENSITIVE
    );
    private static final Pattern ENCRYPTED_SECRET = Pattern.compile(
        "enc:v2:[A-Za-z0-9_-]+:[A-Za-z0-9+/=_-]+"
    );
    private static final Pattern SENSITIVE_KEY_VALUE = Pattern.compile(
        "(?i)\\b([A-Za-z0-9_.-]*(?:password|passwd|pwd|token|secret|private[_-]?key|gitlab\\.token|nexus\\.pass|DEPLOYOPS_DB_PASS)[A-Za-z0-9_.-]*\\s*[=:]\\s*)(\"[^\"]*\"|'[^']*'|[^\\s;&|]+)"
    );
    private static final Pattern SENSITIVE_OPTION = Pattern.compile(
        "(?i)(--(?:password|token|secret|private-key|registry-password|gitlab-token|nexus-pass)\\s+)(\"[^\"]*\"|'[^']*'|[^\\s;&|]+)"
    );
    private static final Pattern SENSITIVE_HEADER = Pattern.compile(
        "(?i)\\b((?:PRIVATE-TOKEN|JOB-TOKEN|Authorization)\\s*[:=]\\s*)(Bearer\\s+)?([^\\s,;]+)"
    );
    private static final Pattern BEARER_TOKEN = Pattern.compile(
        "(?i)\\b(Bearer\\s+)([A-Za-z0-9._~+/=-]{8,})"
    );
    private static final Pattern SSH_IDENTITY_FILE = Pattern.compile(
        "(?i)(^|\\s)(-i\\s+)(\"[^\"]*\"|'[^']*'|[^\\s]+)"
    );
    private static final Pattern URL_CREDENTIAL = Pattern.compile(
        "(https?://)([^\\s/@:]+):([^\\s/@]+)@",
        Pattern.CASE_INSENSITIVE
    );

    public String mask(String value) {
        if (value == null || value.isBlank()) {
            return value;
        }

        String masked = PRIVATE_KEY_BLOCK.matcher(value).replaceAll(MASK);
        masked = ENCRYPTED_SECRET.matcher(masked).replaceAll("enc:v2:" + MASK);
        masked = replaceGroup(masked, SENSITIVE_KEY_VALUE, 1, MASK);
        masked = replaceGroup(masked, SENSITIVE_OPTION, 1, MASK);
        masked = replaceHeader(masked);
        masked = replaceGroup(masked, BEARER_TOKEN, 1, MASK);
        masked = replaceSshIdentityFile(masked);
        masked = URL_CREDENTIAL.matcher(masked).replaceAll("$1$2:" + MASK + "@");
        return masked;
    }

    private String replaceHeader(String value) {
        Matcher matcher = SENSITIVE_HEADER.matcher(value);
        StringBuffer buffer = new StringBuffer();
        while (matcher.find()) {
            String bearer = matcher.group(2) == null ? "" : matcher.group(2);
            matcher.appendReplacement(buffer, Matcher.quoteReplacement(matcher.group(1) + bearer + MASK));
        }
        matcher.appendTail(buffer);
        return buffer.toString();
    }

    private String replaceSshIdentityFile(String value) {
        Matcher matcher = SSH_IDENTITY_FILE.matcher(value);
        StringBuffer buffer = new StringBuffer();
        while (matcher.find()) {
            matcher.appendReplacement(buffer, Matcher.quoteReplacement(matcher.group(1) + matcher.group(2) + MASK));
        }
        matcher.appendTail(buffer);
        return buffer.toString();
    }

    private String replaceGroup(String value, Pattern pattern, int clearGroup, String replacement) {
        Matcher matcher = pattern.matcher(value);
        StringBuffer buffer = new StringBuffer();
        while (matcher.find()) {
            matcher.appendReplacement(buffer, Matcher.quoteReplacement(matcher.group(clearGroup) + replacement));
        }
        matcher.appendTail(buffer);
        return buffer.toString();
    }
}
