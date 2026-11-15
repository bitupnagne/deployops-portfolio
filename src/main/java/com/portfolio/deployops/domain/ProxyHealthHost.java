package com.portfolio.deployops.domain;

/** HTTP Host 값만 허용한다. 연결 대상은 항상 원격 서버의 loopback이다. */
public final class ProxyHealthHost {
    private ProxyHealthHost() { }

    public static String normalize(String value) {
        if (value == null) return null;
        if (value.chars().anyMatch(Character::isISOControl)) {
            throw new IllegalArgumentException("HTTP Host에 제어 문자를 사용할 수 없습니다.");
        }
        String host = value.trim();
        if (host.isEmpty()) return null;
        if (host.length() > 253 || !host.matches("[A-Za-z0-9](?:[A-Za-z0-9-]{0,61}[A-Za-z0-9])?(?:\\.[A-Za-z0-9](?:[A-Za-z0-9-]{0,61}[A-Za-z0-9])?)*")) {
            throw new IllegalArgumentException("HTTP Host는 포트·경로·와일드카드 없는 호스트 이름이어야 합니다.");
        }
        return host;
    }
}
