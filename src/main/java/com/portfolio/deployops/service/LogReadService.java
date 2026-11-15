package com.portfolio.deployops.service;

import com.portfolio.deployops.config.SecurityUserDetails;
import com.portfolio.deployops.domain.Project;
import com.portfolio.deployops.domain.Server;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.ArrayList;
import java.util.List;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

/**
 * AI가 분석할 로그를 <b>읽기 전용</b>으로 가져온다.
 *
 * <p>경로는 <b>프로젝트별</b>로 관리한다: 관리자가 각 프로젝트 상세 화면에서 설정하며
 * {@code system_setting} 의 {@code ai.logpath.<projectId>} 에 저장된다(한 줄당 {@code 소스 | 경로}).
 * 사용자/LLM은 파일 경로를 지정할 수 없고 <b>프로젝트 단위</b>로만 요청하며, 관리자가 허용한 경로만 읽는다.
 * 읽은 내용은 {@link SecretMaskingService} 로 마스킹하고 최근 일부(tail)만 잘라 크기를 제한한다.
 */
@Service
@RequiredArgsConstructor
public class LogReadService {

    private static final int TAIL_BYTES = 16000;
    private static final int MAX_EXCERPT_CHARS = 4000;

    private final SystemSettingService settingService;
    private final ServerService serverService;
    private final ProjectService projectService;
    private final SshCommandService sshCommandService;
    private final SecretMaskingService secretMaskingService;

    private String rawFor(Long projectId) {
        return settingService.get("ai.logpath." + projectId, "");
    }

    /** 로그 경로가 설정된 프로젝트명 목록. <b>로그인 사용자가 볼 수 있는 프로젝트만</b>. */
    public List<String> projectsWithLogs(SecurityUserDetails currentUser) {
        List<String> names = new ArrayList<>();
        for (Project p : projectService.findVisibleAll(currentUser)) {
            String raw = rawFor(p.getId());
            if (raw != null && !raw.isBlank() && p.getName() != null) {
                names.add(p.getName());
            }
        }
        return names;
    }

    /**
     * 프로젝트에 설정된 로그들을 tail + 마스킹해 반환. 화이트리스트(설정된 경로) 밖은 절대 못 읽으며,
     * <b>로그인 사용자가 볼 수 있는 프로젝트</b>만 대상으로 한다(권한 밖 프로젝트 로그는 못 읽음).
     */
    public String readForProject(String projectName, SecurityUserDetails currentUser) {
        if (projectName == null || projectName.isBlank()) {
            return "";
        }
        Project project = projectService.findVisibleAll(currentUser).stream()
            .filter(p -> p.getName() != null && p.getName().equalsIgnoreCase(projectName.trim()))
            .findFirst()
            .orElse(null);
        if (project == null) {
            return "";
        }
        String raw = rawFor(project.getId());
        if (raw == null || raw.isBlank()) {
            return "";
        }

        StringBuilder sb = new StringBuilder();
        for (String line : raw.split("\\r?\\n")) {
            String trimmed = line.trim();
            if (trimmed.isEmpty() || trimmed.startsWith("#")) {
                continue;
            }
            String[] parts = trimmed.split("\\|");
            if (parts.length < 2) {
                continue;
            }
            String source = parts[0].trim();
            String path = parts[1].trim();
            if (source.isEmpty() || path.isEmpty()) {
                continue;
            }
            sb.append("── ").append(source).append(':').append(path).append(" ──\n");
            try {
                sb.append(readTail(source, path)).append("\n\n");
            } catch (Exception ex) {
                sb.append("(읽기 실패: ").append(shortMsg(ex)).append(")\n\n");
            }
        }
        if (sb.length() == 0) {
            return "";
        }
        String masked = secretMaskingService.mask(sb.toString());
        return masked.length() > MAX_EXCERPT_CHARS
            ? "...(앞부분 생략)\n" + masked.substring(masked.length() - MAX_EXCERPT_CHARS)
            : masked;
    }

    private String readTail(String source, String path) throws IOException {
        validatePath(path);
        if ("local".equalsIgnoreCase(source)) {
            return localTail(path);
        }
        Server server = serverService.findAll().stream()
            .filter(s -> source.equalsIgnoreCase(s.getName()))
            .findFirst()
            .orElseThrow(() -> new IllegalArgumentException("서버 '" + source + "'를 찾을 수 없음"));
        RemoteCommandResult result = sshCommandService.execute(
            server, List.of("tail", "-c", String.valueOf(TAIL_BYTES), path));
        if (result.exitCode() != 0) {
            String err = result.stderr() == null ? "" : result.stderr();
            return "(원격 tail 실패 exit=" + result.exitCode() + ") " + err;
        }
        return result.stdout();
    }

    private String localTail(String path) throws IOException {
        Path file = Path.of(path);
        if (!Files.exists(file) || !Files.isRegularFile(file)) {
            return "(로컬 파일 없음: " + path + ")";
        }
        long size = Files.size(file);
        long from = Math.max(0, size - TAIL_BYTES);
        int len = (int) Math.min(TAIL_BYTES, size);
        try (FileChannel channel = FileChannel.open(file, StandardOpenOption.READ)) {
            ByteBuffer buffer = ByteBuffer.allocate(len);
            channel.position(from);
            channel.read(buffer);
            return new String(buffer.array(), 0, buffer.position(), StandardCharsets.UTF_8);
        }
    }

    /** 관리자 설정 경로라도 방어적으로 상위 경로 탐색을 차단한다. */
    private void validatePath(String path) {
        if (path == null || path.isBlank()) {
            throw new IllegalArgumentException("빈 경로");
        }
        if (path.contains("..")) {
            throw new IllegalArgumentException("상위 경로(..)는 허용되지 않음");
        }
    }

    private String shortMsg(Exception ex) {
        String msg = ex.getMessage();
        if (msg == null) {
            return ex.getClass().getSimpleName();
        }
        return msg.length() > 120 ? msg.substring(0, 120) + "..." : msg;
    }
}
