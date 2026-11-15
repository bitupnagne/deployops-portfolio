package com.portfolio.deployops.view;

import com.portfolio.deployops.domain.NginxChangeRequest;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

/** 저장된 원문을 바꾸지 않고 Nginx 변경 화면의 실패 사유만 정리한다. */
public final class NginxFailurePresentation {
    private static final Pattern LOCALE_WARNING = Pattern.compile(
        "^(?:bash|sh|/bin/bash|/bin/sh): warning: setlocale: LC_ALL: cannot change locale \\([A-Za-z0-9_.@-]+\\)$");
    private static final String FALLBACK = "원격 적용에 실패했습니다. 서버 로그에서 상세 원인을 확인해 주세요.";

    private NginxFailurePresentation() {}

    public static Map<Long, String> displayErrors(List<NginxChangeRequest> changes) {
        Map<Long, String> display = new LinkedHashMap<>();
        changes.forEach(change -> display.put(change.getId(), format(change.getErrorMessage())));
        return display;
    }

    public static String format(String raw) {
        if (raw == null || raw.isBlank()) return "";
        String visible = raw.lines().filter(line -> !LOCALE_WARNING.matcher(line.strip()).matches())
            .collect(Collectors.joining("\n")).strip();
        return visible.isEmpty() ? FALLBACK : visible;
    }
}
