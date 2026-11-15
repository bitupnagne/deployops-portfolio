package com.portfolio.deployops.service;
import com.portfolio.deployops.domain.Server;
import java.nio.file.Path;
import java.util.List;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

/** Compatibility adapter for existing callers. Remote work requires an enrolled agent. */
@Service
@RequiredArgsConstructor
public class SshCommandService {
    private final SystemSettingService settingService;
    private AgentDispatchService agentDispatchService;
    @org.springframework.beans.factory.annotation.Autowired
    void configureAgentDispatch(AgentDispatchService service) { this.agentDispatchService = service; }

    public RemoteCommandResult copyFile(Server server, Path localFile, String remotePath) {
        return requireAgent(server).copyFile(server, localFile, remotePath);
    }
    public RemoteCommandResult execute(Server server, List<String> args) {
        return executeInternal(server, args, null, false);
    }
    public RemoteCommandResult executeSensitive(Server server, List<String> args, byte[] stdin) {
        validateInput(stdin);
        return executeInternal(server, args, stdin, true);
    }
    public RemoteCommandResult executeWithInput(Server server, List<String> args, byte[] stdin) {
        validateInput(stdin);
        return executeInternal(server, args, stdin, false);
    }
    private void validateInput(byte[] stdin) {
        if (stdin == null || stdin.length > 65536)
            throw new RemoteExecutionException("에이전트 입력 크기를 확인해 주세요.");
    }
    private RemoteCommandResult executeInternal(Server server, List<String> args, byte[] stdin, boolean sensitive) {
        AgentDispatchService agent = requireAgent(server);
        if (args == null || args.isEmpty()) throw new RemoteExecutionException("에이전트 명령이 비어 있습니다.");
        int timeout = 240;
        try { timeout = Integer.parseInt(settingService.get("deploy.agent.command-timeout-sec", "240")); }
        catch (NumberFormatException ignored) { }
        if (timeout < 1 || timeout > 1800) timeout = 240;
        return agent.execute(server, args, stdin, sensitive, minimumStopGraceTimeout(args, timeout));
    }
    private AgentDispatchService requireAgent(Server server) {
        if (server == null) throw new RemoteExecutionException("대상 서버를 확인해 주세요.");
        if (!server.isAgentControlled())
            throw new RemoteExecutionException("이 서버는 에이전트 전환이 필요합니다. 서버 설정을 저장하고 에이전트를 등록·승인한 뒤 다시 실행해 주세요.");
        if (agentDispatchService == null)
            throw new RemoteExecutionException("에이전트 실행 서비스를 사용할 수 없습니다. SSH 실행은 지원하지 않습니다.");
        return agentDispatchService;
    }
    static int minimumStopGraceTimeout(List<String> args, int configured) {
        int index=args.indexOf("--stop-grace-seconds");
        if(index<0) return configured;
        if(index+1>=args.size() || args.lastIndexOf("--stop-grace-seconds")!=index
            || !args.get(index+1).matches("[1-9][0-9]{0,2}")) throw new RemoteExecutionException("종료 유예 시간 형식이 올바르지 않습니다.");
        int grace=com.portfolio.deployops.domain.StopGrace.seconds(Integer.valueOf(args.get(index+1)));
        // 종료 유예 + 기동/HTTP 헬스 확인 + 실패 시 정리용 두 번째 stop의 유예 + 에이전트 응답 여유.
        return Math.max(configured,grace*2+180);
    }

    static String shellQuote(String value) {
        if (value == null || value.isEmpty()) {
            return "''";
        }
        // Windows OpenSSH can consume literal double quotes while rebuilding its
        // remote command line. Keep that transport entirely free of double quotes.
        // The inner command is explicitly bash -lc, so an ANSI-C quote fragment
        // can express a literal double quote without changing the argument bytes.
        return "'" + value.replace("'", "'\\''").replace("\"", "'$'\\x22''") + "'";
    }
}
