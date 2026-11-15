package com.portfolio.deployops.service;

/** 등록된 주소 문자열의 동일성만 비교한다. 다른 NIC IP가 같은 VM인지는 별도 인벤토리 확인이 필요하다. */
public final class ServerHostIdentity {
    private ServerHostIdentity() {}

    /** DNS 조회 없이 비교한다. IPv4 매핑 IPv6와 모든 loopback 표기를 같은 호스트로 취급한다. */
    public static String canonical(String address) {
        if (address == null || address.isBlank()) return null;
        String value = address.trim();
        if ("localhost".equalsIgnoreCase(value) || "localhost.".equalsIgnoreCase(value)) return "loopback";
        if (value.startsWith("[") && value.endsWith("]")) value = value.substring(1, value.length() - 1);
        if (value.contains("%")) return null;
        if (value.indexOf(':') < 0) {
            if (!strictIpv4(value)) return null;
        } else {
            if (!value.matches("[0-9A-Fa-f:.]+")) return null;
            if (value.contains(".") && !strictIpv4(value.substring(value.lastIndexOf(':') + 1))) return null;
        }
        try {
            var ip = java.net.InetAddress.getByName(value);
            return ip.isLoopbackAddress() ? "loopback" : java.util.HexFormat.of().formatHex(ip.getAddress());
        } catch (java.net.UnknownHostException ex) {
            return null;
        }
    }

    private static boolean strictIpv4(String value) {
        String[] parts = value.split("\\.", -1);
        if (parts.length != 4) return false;
        for (String part : parts) {
            if (!part.matches("0|[1-9][0-9]{0,2}") || Integer.parseInt(part) > 255) return false;
        }
        return true;
    }

}
