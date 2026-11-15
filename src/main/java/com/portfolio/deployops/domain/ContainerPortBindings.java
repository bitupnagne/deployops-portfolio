package com.portfolio.deployops.domain;

import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.stream.Collectors;

/** Explicit TCP host:container bindings; never accepts Docker options, addresses or port ranges. */
public final class ContainerPortBindings {
    private ContainerPortBindings() { }

    public static String normalize(String raw) {
        if (raw == null || raw.isBlank()) return null;
        if (raw.length() > 2048) throw invalid();
        Map<Integer, Integer> ports = new TreeMap<>();
        for (String entry : raw.split(",", -1)) {
            String value = entry.trim();
            if (!value.matches("[0-9]{1,5}\\s*:\\s*[0-9]{1,5}")) throw invalid();
            String[] pair = value.split(":");
            int host = Integer.parseInt(pair[0].trim());
            int container = Integer.parseInt(pair[1].trim());
            if (host < 1 || host > 65535 || container < 1 || container > 65535) throw invalid();
            if (ports.putIfAbsent(host, container) != null) {
                throw new IllegalArgumentException("컨테이너 게시 포트에 같은 호스트 포트를 중복 지정할 수 없습니다.");
            }
        }
        return ports.entrySet().stream().map(port -> port.getKey() + ":" + port.getValue())
            .collect(Collectors.joining(","));
    }

    public static List<String> bindings(String raw) {
        String normalized = normalize(raw);
        return normalized == null ? List.of() : List.of(normalized.split(","));
    }

    private static IllegalArgumentException invalid() {
        return new IllegalArgumentException("컨테이너 게시 포트는 1~65535 범위의 호스트:컨테이너를 쉼표로 구분해 입력하세요. TCP만 지원합니다.");
    }
}
