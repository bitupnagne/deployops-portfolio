package com.portfolio.deployops.service;

import com.portfolio.deployops.domain.Server;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Stream;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

/**
 * 서버 파일시스템을 <b>읽기 전용</b>으로 훑어본다 — AI 로그 경로를 손으로 받아적지 않고 고르게 하기 위한 것.
 *
 * <p>새로운 권한을 주는 기능이 아니다. 로그 경로 입력란은 이미 임의 경로를 받아들이므로,
 * 여기서 하는 일은 "이미 칠 수 있던 경로를 눈으로 확인하게" 해 주는 것뿐이다. 그래도 범위는 좁힌다:
 * <ul>
 *   <li>디렉터리 목록만 반환한다. 파일 <b>내용</b>은 이 서비스로 나가지 않는다(그건 {@link LogReadService} 담당).</li>
 *   <li>{@code ..} 를 막아 경로 조작으로 화이트리스트를 우회하지 못하게 한다.</li>
 *   <li>원격은 {@code find -maxdepth 1} 한 번으로 끝난다. 셸에 붙는 인자는
 *       {@link SshCommandService} 가 따옴표로 감싸므로 명령이 쪼개지지 않는다.</li>
 * </ul>
 */
@Service
@RequiredArgsConstructor
public class FileBrowseService {

    /** 한 디렉터리에서 돌려줄 최대 항목 수. /usr/bin 처럼 수천 개짜리를 그대로 실어 보내지 않는다. */
    private static final int MAX_ENTRIES = 500;

    private final ServerService serverService;
    private final SshCommandService sshCommandService;

    /**
     * @param source {@code local}(배포시스템 호스트) 또는 서버 이름
     * @param path   훑어볼 디렉터리 절대경로
     * @return {@code path}, {@code parent}, {@code entries[{name,type,size,modified}]}
     */
    public Map<String, Object> browse(String source, String path) {
        String dir = normalize(path);
        List<Map<String, Object>> entries = "local".equalsIgnoreCase(source)
            ? browseLocal(dir)
            : browseRemote(resolveServer(source), dir);

        entries.sort(Comparator
            .comparing((Map<String, Object> e) -> "dir".equals(e.get("type")) ? 0 : 1)
            .thenComparing(e -> String.valueOf(e.get("name")).toLowerCase()));

        boolean truncated = entries.size() > MAX_ENTRIES;
        if (truncated) {
            entries = new ArrayList<>(entries.subList(0, MAX_ENTRIES));
        }

        Map<String, Object> result = new LinkedHashMap<>();
        result.put("source", source);
        result.put("path", dir);
        result.put("parent", parentOf(dir));
        result.put("entries", entries);
        result.put("truncated", truncated);
        return result;
    }

    private Server resolveServer(String source) {
        return serverService.findAll().stream()
            .filter(s -> s.getName() != null && s.getName().equalsIgnoreCase(source))
            .findFirst()
            .orElseThrow(() -> new IllegalArgumentException("서버 '" + source + "'를 찾을 수 없습니다."));
    }

    private List<Map<String, Object>> browseLocal(String dir) {
        Path base = Path.of(dir);
        if (!Files.isDirectory(base)) {
            throw new IllegalArgumentException("디렉터리가 아닙니다: " + dir);
        }
        List<Map<String, Object>> out = new ArrayList<>();
        try (Stream<Path> stream = Files.list(base)) {
            for (Path p : stream.toList()) {
                Map<String, Object> e = new LinkedHashMap<>();
                e.put("name", p.getFileName().toString());
                boolean isDir = Files.isDirectory(p);
                e.put("type", isDir ? "dir" : "file");
                long size = 0L;
                try {
                    size = isDir ? 0L : Files.size(p);
                } catch (IOException ignored) {
                    // 권한이 없어 크기를 못 읽는 항목은 0으로 두고 목록에는 남긴다 — 존재 자체가 정보다.
                }
                e.put("size", size);
                out.add(e);
            }
        } catch (IOException ex) {
            throw new IllegalArgumentException("디렉터리를 읽지 못했습니다: " + shortMsg(ex));
        }
        return out;
    }

    private List<Map<String, Object>> browseRemote(Server server, String dir) {
        // find 한 번으로 종류·크기·이름을 탭으로 끊어 받는다.
        //   ls -l 출력은 로케일·심볼릭링크·공백 들어간 이름에서 파싱이 깨진다. printf 포맷이 안전하다.
        //   -L 로 심볼릭 링크는 대상 기준으로 판정한다(로그 디렉터리가 링크인 경우가 흔하다).
        RemoteCommandResult result = sshCommandService.execute(server, List.of(
            "find", "-L", dir, "-maxdepth", "1", "-mindepth", "1",
            "-printf", "%y\\t%s\\t%f\\n"
        ));
        if (result.exitCode() != 0) {
            String err = result.stderr() == null ? "" : result.stderr().trim();
            throw new IllegalArgumentException("디렉터리를 읽지 못했습니다 (exit=" + result.exitCode() + ") " + err);
        }

        List<Map<String, Object>> out = new ArrayList<>();
        for (String line : result.stdout().split("\\r?\\n")) {
            if (line.isBlank()) {
                continue;
            }
            String[] parts = line.split("\\t", 3);
            if (parts.length < 3) {
                continue;
            }
            Map<String, Object> e = new LinkedHashMap<>();
            e.put("name", parts[2]);
            e.put("type", "d".equals(parts[0]) ? "dir" : "file");
            long size;
            try {
                size = Long.parseLong(parts[1].trim());
            } catch (NumberFormatException ex) {
                size = 0L;
            }
            e.put("size", "d".equals(parts[0]) ? 0L : size);
            out.add(e);
        }
        return out;
    }

    /** 절대경로만 허용하고 {@code ..} 를 막는다. 뒤에 붙은 {@code /} 는 떼어 비교를 단순화한다. */
    private String normalize(String path) {
        String p = (path == null || path.isBlank()) ? "/" : path.trim();
        if (!p.startsWith("/")) {
            throw new IllegalArgumentException("절대경로만 사용할 수 있습니다: " + p);
        }
        if (p.contains("..")) {
            throw new IllegalArgumentException("상위 경로(..)는 사용할 수 없습니다.");
        }
        p = p.replaceAll("/{2,}", "/");
        if (p.length() > 1 && p.endsWith("/")) {
            p = p.substring(0, p.length() - 1);
        }
        return p;
    }

    private String parentOf(String dir) {
        if ("/".equals(dir)) {
            return null;
        }
        int idx = dir.lastIndexOf('/');
        return idx <= 0 ? "/" : dir.substring(0, idx);
    }

    private String shortMsg(Exception ex) {
        String msg = ex.getMessage();
        if (msg == null) {
            return ex.getClass().getSimpleName();
        }
        return msg.length() > 120 ? msg.substring(0, 120) + "..." : msg;
    }
}
