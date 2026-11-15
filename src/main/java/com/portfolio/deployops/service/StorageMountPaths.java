package com.portfolio.deployops.service;

import java.util.List;
import org.springframework.http.HttpStatus;
import org.springframework.web.server.ResponseStatusException;

/** Linux paths only. Native checks additionally reject symlinks and verify the real filesystem. */
public final class StorageMountPaths {
    private StorageMountPaths() { }
    public static void key(String value) {
        if (value == null || !value.matches("[a-z][a-z0-9_-]{0,63}")) throw invalid("저장소 키는 영문 소문자로 시작하는 64자 이내 영문·숫자·밑줄·하이픈입니다.");
    }
    public static void root(String value) {
        absolute(value);
        if (List.of("/bin","/boot","/dev","/etc","/lib","/lib64","/proc","/root","/run","/sbin","/sys","/usr","/var/run","/var/lib/docker","/var/lib/deploy-system","/var/lib/containerd").stream().anyMatch(p -> overlaps(p,value)))
            throw invalid("시스템·Docker·배포 에이전트 경로는 저장소로 등록할 수 없습니다.");
    }
    public static void target(String value) {
        absolute(value);
        if (List.of("/bin","/boot","/dev","/etc","/lib","/lib64","/proc","/root","/run","/sbin","/sys","/usr","/app/logs","/tmp").stream().anyMatch(p -> overlaps(p,value)))
            throw invalid("컨테이너 시스템 경로와 관리 로그·임시 경로는 사용할 수 없습니다.");
    }
    public static void absolute(String value) {
        if (value == null || value.length()>512 || !value.matches("/[A-Za-z0-9_+.-]+(?:/[A-Za-z0-9_+.-]+)*") || segmentsInvalid(value)) throw invalid("공백·상위 경로 이동·끝 슬래시 없는 Linux 절대 경로를 입력해 주세요.");
    }
    public static void relative(String value) {
        if (value == null || value.length()>512 || !(value.isEmpty() || value.matches("[A-Za-z0-9_+.-]+(?:/[A-Za-z0-9_+.-]+)*")) || segmentsInvalid(value)) throw invalid("상대 경로에는 상위 경로 이동이나 공백을 사용할 수 없습니다.");
    }
    public static boolean overlaps(String a,String b) { return a.equals(b)||a.startsWith(b+"/")||b.startsWith(a+"/"); }
    private static boolean segmentsInvalid(String value) { return java.util.Arrays.stream(value.split("/")).anyMatch(p -> p.equals(".")||p.equals("..")); }
    public static ResponseStatusException invalid(String message) { return new ResponseStatusException(HttpStatus.BAD_REQUEST,message); }
}
