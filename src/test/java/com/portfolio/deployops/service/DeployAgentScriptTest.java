package com.portfolio.deployops.service;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import org.junit.jupiter.api.Test;

/**
 * 배포 에이전트 정본 소스(installer/deploy-agent)의 핵심 안전장치를 검증한다.
 * (구 offline-installer 경로에서 2026-06-11 이전됨)
 */
class DeployAgentScriptTest {
    @Test
    void nginxPolicySupportsApprovedStaticSpaAndStrictStaticModes() throws Exception {
        String script = Files.readString(Path.of(
            "installer", "deploy-agent", "web", "deploy-nginx-policy.sh"));

        assertThat(script)
            .contains("STATIC_SPA")
            .contains("STATIC_FILES")
            .contains("root %s/current")
            .contains("try_files $uri $uri/ /index.html")
            .contains("try_files $uri $uri/ =404")
            .contains("safe_web_root")
            .contains("/(var/www|srv/web)/");
    }

    /*
      restore_backup 의 마지막 문장이 `[[ -d ... ]] && cp` 형태면, 백업에 tls 디렉터리가
      없을 때(= 그 정책의 <최초 적용>, tls.absent) 조건이 거짓이라 그 1 이 함수의 반환값이
      된다. 에이전트는 set -Eeuo pipefail 아래라 호출부가 아무 메시지 없이 즉사한다.

      실제로 겪은 것 — 롤백이 출력 한 줄 없이 exit 1 로 끝났다. 운영자 눈에는 되돌린
      것처럼 보이고, apply 실패 시 자동복구 경로(200/207/211행)도 같은 함수를 쓰므로
      "복구했습니다" 메시지조차 못 찍는다. 신규 설치에서만 드러나는 부류라 문자열로 고정한다.
    */
    @Test
    void nginxPolicyRestoreSucceedsWhenTheBackupHasNoTlsDirectory() throws Exception {
        String script = Files.readString(Path.of(
            "installer", "deploy-agent", "web", "deploy-nginx-policy.sh"));

        assertThat(script)
            .as("restore_backup 은 if/fi 로 끝나야 한다")
            .contains("if [[ -d \"$backup/tls\" ]]; then cp -a \"$backup/tls\" \"$tls_dir\"; fi");
        assertThat(script)
            .as("TLS 를 끈 정책도 apply 가 0 으로 끝나야 한다")
            .contains("if [[ \"$TLS\" == \"true\" ]]; then");
    }

    /*
      같은 함정을 두 번 밟았다 — restore_backup 은 최초 적용 백업에서, apply_policy 는
      TLS 를 끈 정책에서 각각 조용히 1 을 반환했다. 둘 다 원인은 하나다:

          어떤_함수(){
            ...
            [[ 조건 ]] && 명령      # ← 함수의 마지막 문장
          }

      조건이 거짓이면 그 1 이 함수의 반환값이 된다. 에이전트는 set -Eeuo pipefail 아래라
      호출부가 메시지 없이 죽거나, 스크립트가 통째로 exit 1 로 끝나 배포시스템이 성공한
      작업을 실패로 기록한다. 하필 <옵션을 끈 경로>에서만 터져서 정상 경로 시험으로는
      드러나지 않는다.

      개별 사례를 문자열로 잡는 대신 형태 자체를 금지한다.
      (여는 중괄호로 이어지는 `&& {` 다중행 형태는 이 검사가 보지 못한다 — 그 경우
       함수 끝에 오지 않도록 하는 것은 여전히 사람 몫이다.)
    */
    @Test
    void agentScriptsNeverEndAFunctionWithABareConditionalAnd() throws Exception {
        Path agentRoot = Path.of("installer", "deploy-agent");
        var offenders = new java.util.ArrayList<String>();

        try (var files = Files.walk(agentRoot)) {
            for (Path script : files.filter(p -> p.toString().endsWith(".sh")).sorted().toList()) {
                List<String> lines = Files.readAllLines(script);
                for (int i = 0; i < lines.size() - 1; i++) {
                    boolean conditionalAnd = lines.get(i).stripLeading().startsWith("[[")
                        && lines.get(i).contains("]] &&")
                        && !lines.get(i).contains("||");
                    boolean nextClosesFunction = lines.get(i + 1).strip().equals("}");
                    if (conditionalAnd && nextClosesFunction) {
                        offenders.add("%s:%d  %s".formatted(
                            script.getFileName(), i + 1, lines.get(i).strip()));
                    }
                }
            }
        }

        assertThat(offenders)
            .as("함수의 마지막 문장이 `[[ ]] && cmd` 면 조건이 거짓일 때 함수가 1 을 반환한다")
            .isEmpty();
    }

    @Test
    void webContainerDelegatesToOwnedCoreAndRestoresContainerWhenNginxReloadFails() throws Exception {
        String script = Files.readString(Path.of(
            "installer", "deploy-agent", "web", "deploy-web-app.sh"
        ));

        assertThat(script)
            .contains("/usr/local/deploy/bin/deploy-container-core.sh")
            .contains("! -L \"$core\"")
            .contains("'0:0:750'")
            .contains("DEPLOY_CONTAINER_ROLE=web")
            .contains("systemctl is-active --quiet nginx")
            .contains("nginx -t")
            .contains("systemctl reload nginx")
            .contains("web_core restore \"${args[@]}\"")
            .contains("WEB_CONTAINER_RESTORED=true")
            .contains("NGINX_RECOVERY_REQUIRED=true")
            .doesNotContain("render_nginx_config", "proxy_pass", "/etc/nginx/conf.d/");
        String installer = Files.readString(Path.of("installer/deploy-agent/install-deploy-agent.sh"));
        assertThat(installer).contains("deploy-container-core.sh", "was/deploy-was-app.sh");
        String sudoers = Files.readString(Path.of("installer/deploy-agent/sudoers-render.sh"));
        assertThat(sudoers).doesNotContain("deploy-container-core.sh");
    }

    /*
      conf.d 에 설정을 쓰는 주체가 넷이다 — 설치기(officesystem.conf) · 승인형 정책 ·
      web-proxy · web-app. 파일명 규칙이 겹쳐서 서로를 덮어쓸 수 있는데, 특히 승인 없이
      도는 배포 경로가 <승인받은 정책 파일>을 덮으면 백업·롤백 체인이 남의 설정을 자기
      것으로 착각한다. 그리고 같은 (listen, server_name) 이 겹치면 nginx 가 나중 것을
      조용히 무시해서, nginx -t 는 통과하고 배포도 성공인데 서비스만 되지 않는다.
    */
    @Test
    void nginxConfigGeneratorsRefuseToOverwriteEachOtherAndDetectServerConflicts() throws Exception {
        for (String name : List.of("deploy-web-proxy.sh", "deploy-nginx-policy.sh")) {
            String script = Files.readString(Path.of("installer", "deploy-agent", "web", name));
            assertThat(script).as("%s: 다른 생성기의 파일을 덮지 않는다", name)
                .contains("assert_owns_config");
            assertThat(script).as("%s: server 블록 충돌을 사전에 잡는다", name)
                .contains("assert_no_conflicting_server");
        }
    }

    @Test
    void allWebWritersShareNginxLockWithoutReusingTheContainerCoreDescriptor() throws Exception {
        for (String name : List.of("deploy-web-app.sh", "deploy-web-static.sh",
            "deploy-web-proxy.sh", "deploy-nginx-policy.sh")) {
            String script = Files.readString(Path.of("installer", "deploy-agent", "web", name));
            assertThat(script).as(name)
                .contains("exec 8>/var/lock/deploy-ops-nginx.lock")
                .contains("flock -n 8");
        }
    }

    /*
      sudoers 는 <스크립트>만 제한한다 — 인자는 막지 못한다. 설정 파일 경로를 임의로
      받으면 deploy 계정이 root 로 아무 파일이나 덮어쓸 수 있다
      (--nginx-conf /etc/nginx/nginx.conf 로 주 설정 파괴 등).
    */
    /*
      "배포는 성공했는데 서비스가 안 된다" 는 이 시스템에서 가장 찾기 어려운 부류다.
      두 원인 모두 nginx -t 를 통과하기 때문에 배포 로그만 봐서는 알 수 없다 —
      upstream 오타(502)와 web-root 를 가리키는 server 블록 부재(파일만 올라감).
      막지는 않되(설정을 나중에 넣는 순서가 정상인 구성이 있다) 로그에 이유를 남긴다.
    */
    @Test
    void agentsWarnWhenTheDeploymentWouldNotActuallyServe() throws Exception {
        String policy = Files.readString(Path.of(
            "installer", "deploy-agent", "web", "deploy-nginx-policy.sh"));
        assertThat(policy)
            .as("upstream 도달성을 확인하고 결과를 남긴다")
            .contains("warn_if_upstream_unreachable")
            .contains("UPSTREAM_REACHABLE=");

        String staticAgent = Files.readString(Path.of(
            "installer", "deploy-agent", "web", "deploy-web-static.sh"));
        assertThat(staticAgent)
            .as("활성 설정에서 web-root 경로 root 지시자를 참고용으로 진단한다")
            .contains("warn_if_web_root_not_served")
            .contains("ACTIVE_ROOT_DIRECTIVE=false");
    }

    /*
      ★ WEB 노드는 공개 접점(신뢰 경계)이다. 여기서 X-Forwarded-For 를 append 하면
        최좌측 값을 <클라이언트가 정한다>.

        오피스의 AccessZone 은 "XFF 최좌측이 사설 IP면 내부망" 으로 판정하고, 그 결과로
        외부망 OTP 강제 여부가 갈린다. 즉 append 하는 순간 공격자가 헤더 한 줄
        (`X-Forwarded-For: 10.0.0.1`)로 OTP 를 건너뛴다.

        설치기 components/nginx.sh 는 처음부터 이 이유로 덮어쓰기를 쓰고 주석까지 달아 뒀다.
        에이전트 생성기 셋도 같은 규칙이어야 한다 — 체인 보존보다 신뢰 경계가 우선이다.
        (앞단에 HTTP 프록시를 두어 체인이 필요해지면, 그때는 real_ip 모듈 +
         set_real_ip_from 으로 <신뢰하는 프록시만> 지정하는 방식으로 가야 한다.)
    */
    @Test
    void edgeNginxGeneratorsOverwriteForwardedForInsteadOfAppending() throws Exception {
        for (String name : List.of("deploy-nginx-policy.sh", "deploy-web-proxy.sh")) {
            String script = Files.readString(Path.of("installer", "deploy-agent", "web", name));
            assertThat(script).as("%s: XFF 는 $remote_addr 로 덮어쓴다", name)
                .contains("X-Forwarded-For");
            assertThat(script)
                .as("%s: append 하면 클라이언트가 XFF 최좌측을 정해 외부망 OTP 를 우회한다", name)
                .doesNotContain("X-Forwarded-For $proxy_add_x_forwarded_for")
                .doesNotContain("X-Forwarded-For \\$proxy_add_x_forwarded_for");
        }
    }

    /*
      번들은 3망을 건너와 root 로 풀린다. 해시 검증이 선택이면 "검증했다" 와
      "안 했다" 가 로그에서 구분되지 않고, tar 멤버가 상위 경로를 담으면
      릴리스 디렉터리 밖에 파일을 쓴다.
    */
    @Test
    void staticBundleIntegrityIsMandatoryAndPathsAreConfined() throws Exception {
        String script = Files.readString(Path.of(
            "installer", "deploy-agent", "web", "deploy-web-static.sh"));

        assertThat(script)
            .contains("--bundle-sha256 is required")
            .contains("verify_bundle_members")
            .contains("Bundle contains absolute or parent-relative paths");
        assertThat(script)
            .as("경로를 정규식이 아니라 리터럴로 비교해야 엉뚱한 릴리스로 롤백하지 않는다")
            .contains("grep -Fvx");
    }

    @Test
    void nginxConfigPathIsPinnedToConfD() throws Exception {
        for (String name : List.of("deploy-web-proxy.sh")) {
            String script = Files.readString(Path.of("installer", "deploy-agent", "web", name));
            assertThat(script).as("%s", name)
                .contains("^/etc/nginx/conf\\.d/[A-Za-z0-9][A-Za-z0-9._-]*\\.conf$");
        }
    }

    @Test
    void wasDeployAgentPullsSwapsContainerAndHealthchecks() throws Exception {
        String script = Files.readString(Path.of(
            "installer", "deploy-agent", "was", "deploy-was-app.sh"
        ));

        assertThat(script)
            .contains("HEALTHCHECK_SCRIPT=\"/usr/local/deploy/bin/local-healthcheck.sh\"")
            .contains("docker run -d --name \"$CONTAINER\" --restart \"$restart_policy\"")
            .contains("--image) IMAGE=\"$2\"")
            .contains("--container) CONTAINER=\"$2\"")
            .contains("--health-mode) HEALTH_MODE=\"$2\"")
            .contains("docker container inspect --format '{{.State.Status}} {{.RestartCount}}'")
            .contains("Container running check passed")
            .contains("Health check skipped by explicit mode: none");

        // 일반 컨테이너는 기존 재시작 정책을 유지한다. 저장소가 있는 컨테이너는
        // Docker 자동 재기동을 막고, 마운트를 다시 확인하는 guard만 시작할 수 있다.
        String deployBody = script.substring(script.indexOf("run_deploy(){"));
        assertThat(deployBody.replace("\r\n", "\n"))
            .contains("local restart_policy=unless-stopped\n"
                + "  [[ -z \"$MOUNT_SPEC\" ]] || restart_policy=no\n"
                + "  local run_args=(docker run -d --name \"$CONTAINER\" --restart \"$restart_policy\"")
            .doesNotContain("--restart always");
    }

    @Test
    void bothContainerAgentsSupportExplicitHttpContainerAndNoneHealthModes() throws Exception {
        // WEB executes this exact core; it does not maintain a second health implementation.
        for (String path : List.of("installer/deploy-agent/was/deploy-was-app.sh")) {
            String script = Files.readString(Path.of(path));
            assertThat(script).as(path)
                .contains("HEALTH_MODE=\"http\"")
                .contains("http)")
                .contains("container)")
                .contains("none)")
                .contains("stable >= 3")
                .contains("verify_health");
        }
        String installer = Files.readString(Path.of("installer/deploy-agent/install-deploy-agent.sh"));
        assertThat(installer).contains("install -m 750 -o root -g root \"$SCRIPT_DIR/was/deploy-was-app.sh\" \"$bin_dir/deploy-container-core.sh\"");
        String core = Files.readString(Path.of("installer/deploy-agent/was/deploy-was-app.sh"));
        assertThat(core).contains("web) CONFIG_FILE=\"/usr/local/deploy/conf/web-agent.env\"");
    }

    /**
     * 망분리 구성에서는 대상 서버가 레지스트리에 붙을 수 없어 배포시스템이 이미지 tar 를 밀어 넣는다.
     * tar 를 받으면 pull 하지 않고 load 해야 하며, 받은 tar 안에 요청한 이미지가 실제로 있는지
     * 확인해야 한다(엉뚱한 태그로 컨테이너가 뜨는 것을 막는다). load 후에는 디스크를 위해 지운다.
     */
    /**
     * DMZ WEB 서버에는 docker 를 두지 않는다.
     *
     * <p>정적 산출물은 배포망이 이미지에서 꺼내 tar.gz 로 만들어 보내고, DMZ 는 그것을 풀기만 한다.
     * 공개 접점에 컨테이너 런타임(root 데몬·CIS 점검 항목·CVE 추적 대상)을 늘리지 않기 위한 결정이므로,
     * 이 스크립트에 docker 의존이 다시 들어오면 실패해야 한다.
     */
    @Test
    void staticWebAgentHasNoDockerDependencySoDmzStaysMinimal() throws Exception {
        String script = Files.readString(Path.of(
            "installer", "deploy-agent", "web", "deploy-web-static.sh"
        ));

        // docker 를 "호출"하지 않아야 한다. (안내 문구에 단어가 등장하는 것은 무방)
        assertThat(script)
            .doesNotContain("command -v docker")
            .doesNotContain("docker load")
            .doesNotContain("docker create")
            .doesNotContain("docker cp")
            .doesNotContain("docker run");

        assertThat(script)
            .contains("--bundle) BUNDLE=\"$2\"")
            .contains("--bundle-sha256) BUNDLE_SHA256=\"$2\"")
            // 망을 건너온 번들이므로 풀기 전에 무결성을 확인한다
            .contains("Bundle checksum mismatch")
            // 릴리스 원자 교체(무중단)는 그대로여야 한다
            .contains("mv -Tf \"${current_link}.tmp\" \"$current_link\"");
    }

    @Test
    void wasDeployAgentLoadsImageTarInsteadOfPullingWhenTarProvided() throws Exception {
        String script = Files.readString(Path.of(
            "installer", "deploy-agent", "was", "deploy-was-app.sh"
        ));

        assertThat(script)
            .contains("--image-tar) IMAGE_TAR=\"$2\"")
            .contains("docker load -i \"$archive_dir/image.tar\"")
            .contains("rm -f \"$IMAGE_TAR\"")
            .contains("docker image inspect \"$IMAGE\"");

        // tar 가 없을 때만 레지스트리 로그인/pull 을 타야 한다
        int tarBranch = script.indexOf("if [[ -n \"$IMAGE_TAR\" ]]; then");
        int elseBranch = script.indexOf("registry_login", tarBranch);
        assertThat(tarBranch).isPositive();
        assertThat(elseBranch).isGreaterThan(tarBranch);
    }

    @Test
    void agentInstallerCoversSupportedSingleRolesAndLocalHelpers() throws Exception {
        String script = Files.readString(Path.of(
            "installer", "deploy-agent", "install-deploy-agent.sh"
        ));

        assertThat(script)
            .contains("--type was|web|deploy|nexus|gitlab|runner")
            .contains("--transport must be agent")
            .contains("/etc/sudoers.d/deploy-remote")
            .contains("local-healthcheck.sh")
            .contains("visudo -cf")
            .contains("install_web_extras");
    }

    @Test
    void rockyWebEditorAndTrafficShareInstalledPlatformPythonContract() throws Exception {
        String installer = Files.readString(Path.of("installer", "deploy-agent", "install-deploy-agent.sh"));
        String policy = Files.readString(Path.of("installer", "deploy-agent", "web", "deploy-nginx-policy.sh"));
        assertThat(installer)
            .contains("ensure_file \"$SCRIPT_DIR/web/nginx-rollout.py\"")
            .contains("install -m 640 -o root -g root \"$SCRIPT_DIR/web/nginx-rollout.py\"");
        assertThat(policy)
            .contains("nginx_python_helper(){")
            .contains("python=/usr/libexec/platform-python")
            .contains("[[ -x \"$python\" ]] || python=/usr/bin/python3")
            .contains("\"$python\" -I -B \"$helper\" \"$@\"")
            .contains("nginx_python_helper /usr/local/deploy/bin/nginx-config-validator.py \"$@\"")
            .contains("nginx_python_helper /usr/local/deploy/bin/nginx-rollout.py \"${command_args[@]}\"")
            .doesNotContain("command -v python3", "python3 /usr/local/deploy/bin/nginx-rollout.py");
    }

    @Test
    void staticDeployAgentSwapsReleaseSymlinkAndReloadsNginx() throws Exception {
        String script = Files.readString(Path.of(
            "installer", "deploy-agent", "web", "deploy-web-static.sh"
        ));

        assertThat(script)
            .contains("ln -sfn \"$release_dir\" \"${current_link}.tmp\"")
            .contains("mv -Tf \"${current_link}.tmp\" \"$current_link\"")
            .contains("nginx -t")
            .contains("systemctl reload nginx")
            .contains("Restoring previous release.");
    }

    @Test
    void proxyDeployAgentValidatesBacksUpAndReloadsNginx() throws Exception {
        String script = Files.readString(Path.of(
            "installer", "deploy-agent", "web", "deploy-web-proxy.sh"
        ));

        assertThat(script)
            .contains("nginx -t")
            .contains("systemctl reload nginx")
            .contains("BACKUP_ROOT=\"/var/backups/deploy-system/nginx-proxy\"")
            .contains("prune_backups")
            .contains("restore_config \"$target\" \"$backup\"")
            // static · nginx-policy 와 같이 직렬화한다. 셋이 같은 nginx 를 건드린다.
            .contains("flock -n 9");
    }

    @Test
    void wasAgentCapsContainerMemorySoOneLeakCannotTakeDownTheNode() throws Exception {
        String script = Files.readString(Path.of(
            "installer", "deploy-agent", "was", "deploy-was-app.sh"
        ));

        // 상한이 없으면 컨테이너 안 JVM 이 cgroup 이 아니라 호스트 전체 메모리를 보고 힙을 잡는다.
        // 한 노드에 서비스를 여러 개 올리는 순간 합계가 물리 메모리를 넘겨 엉뚱한 컨테이너가 죽는다.
        assertThat(script)
            .contains("--memory) MEMORY_LIMIT=")
            // swap 을 같은 값으로 묶지 않으면 넘친 만큼 스왑으로 새어 제한이 무의미해진다.
            .contains("--memory-swap")
            // 상한 없이 뜨는 경우를 조용히 넘기지 않는다.
            .contains("WARNING: --memory");
    }

    @Test
    void agentInstallerKeepsDeployAccountOutOfTheDockerGroup() throws Exception {
        String script = Files.readString(Path.of(
            "installer", "deploy-agent", "install-deploy-agent.sh"
        ));

        // docker 소켓은 root 권한과 동등하다. 배포 계정이 docker 그룹에 들어가면
        // sudoers 화이트리스트("에이전트 스크립트만 실행")를 통째로 우회할 수 있고,
        // 그 계정은 배포망에서 들어오는 SSH 키를 물고 있다.
        assertThat(script).doesNotContain("usermod -aG docker");
        // 과거 설치본에서 잘못 들어갔다면 되돌려야 한다.
        assertThat(script).contains("gpasswd -d");
    }

    @Test
    void outboundAgentAllowlistCoversEveryScriptTheDeploySystemInvokes() throws Exception {
        String worker = Files.readString(Path.of(
            "installer", "deploy-agent", "pull", "deploy-pull-agent.py"
        ));
        var definitions = java.util.regex.Pattern.compile(
            "(?m)^(?:COMMON|WAS|WEB|INFRA) = \\{([^\\r\\n]+)\\}");
        var allowed = new java.util.HashSet<String>();
        var definitionsMatcher = definitions.matcher(worker);
        var helperName = java.util.regex.Pattern.compile("\"([A-Za-z0-9._-]+\\.sh)\"");
        while (definitionsMatcher.find()) {
            var names = helperName.matcher(definitionsMatcher.group(1));
            while (names.find()) allowed.add("/usr/local/deploy/bin/" + names.group(1));
        }
        Path sourceRoot = Path.of("src", "main", "java");
        var agentPath = java.util.regex.Pattern.compile("/usr/local/deploy/bin/[A-Za-z0-9._-]+\\.sh");
        var invoked = new java.util.TreeMap<String, String>();
        try (var files = Files.walk(sourceRoot)) {
            for (Path file : files.filter(p -> p.toString().endsWith(".java")).toList()) {
                var matcher = agentPath.matcher(Files.readString(file));
                while (matcher.find()) invoked.putIfAbsent(matcher.group(), file.getFileName().toString());
            }
        }
        assertThat(allowed).as("실제 pull worker의 역할별 명령 허용 목록").isNotEmpty();
        assertThat(invoked).as("실제 Java 호출 경로").isNotEmpty();
        invoked.forEach((script, owner) -> assertThat(allowed)
            .as("%s 가 outbound agent 허용 목록에 없다 (%s 에서 호출)", script, owner)
            .contains(script));
        assertThat(invoked).containsKeys("/usr/local/deploy/bin/deploy-service-control.sh",
            "/usr/local/deploy/bin/deploy-infrastructure.sh");
    }

    @Test
    void wasAgentPrefersTheLocalImageOverReachingForTheRegistry() throws Exception {
        String script = Files.readString(Path.of(
            "installer", "deploy-agent", "was", "deploy-was-app.sh"
        ));

        // 직전 이미지로 되돌릴 때 그 이미지는 이미 이 노드에 있다. 그런데 tar 가 없다는
        // 이유로 레지스트리 pull 로 빠지면, push 방식(3망)에서는 나갈 수 없어
        // "server gave HTTP response to HTTPS client" 같은 엉뚱한 오류로 끝난다.
        assertThat(script).contains("docker image inspect \"$IMAGE\"");
        assertThat(script).contains("Using image already present on this host");

        // 레지스트리가 설정된 경우에만 pull 을 시도해야 한다.
        assertThat(script).contains("elif [[ -n \"$REGISTRY_URL\" ]]; then");
        // 셋 다 아니면 원인을 그대로 말해 준다.
        assertThat(script).contains("Image not found locally and no registry configured");
    }

    @Test
    void readOnlyAgentTakesCapabilityNamesNotShellCommands() throws Exception {
        String script = Files.readString(Path.of(
            "installer", "deploy-agent", "read", "deploy-read.sh"
        ));

        // AI 는 문장을 만들어 내는 도구다. 명령 문자열을 받는 순간 무엇이든 실행될 수 있으므로,
        // 정해진 "기능 이름"만 받고 실제 명령은 스크립트가 조립해야 한다.
        assertThat(script)
            .contains("safe_name()")
            .contains("clamp_lines()")
            .contains("capabilities)");

        // 사용자 입력이 셸로 흘러가는 통로가 없어야 한다.
        assertThat(script)
            .doesNotContain("eval ")
            .doesNotContain("bash -c \"$")
            .doesNotContain("sh -c \"$");

        // 쓰기 동작이 섞이면 "읽기 전용"이 거짓말이 된다.
        assertThat(script)
            .doesNotContain("systemctl restart")
            .doesNotContain("systemctl stop")
            .doesNotContain("systemctl start")
            .doesNotContain("docker run")
            .doesNotContain("docker rm")
            .doesNotContain("docker load")
            .doesNotContain("rm -rf");
    }

    @Test
    void readOnlyAgentMasksSecretsAndCapsOutput() throws Exception {
        String script = Files.readString(Path.of(
            "installer", "deploy-agent", "read", "deploy-read.sh"
        ));

        // 로그에는 토큰·비밀번호가 섞인다. 외부 LLM 으로 나갈 수 있는 경로이므로
        // 서버를 떠나기 전에 지운다.
        assertThat(script)
            .contains("gl(pat|rt)-")          // GitLab 토큰
            .contains("[Bb]earer")            // Authorization 헤더
            .contains("enc:v[0-9]+:")         // 저장 시크릿 봉투
            .contains("MASKED");

        // 출력 상한이 없으면 한 번의 호출로 로그 전체가 밖으로 나가고 토큰 비용도 폭발한다.
        assertThat(script)
            .contains("MAX_BYTES")
            .contains("MAX_LINES")
            .contains("head -c \"$MAX_BYTES\"");
    }

    @Test
    void aiAccountIsSeparateFromTheDeployAccountInSudoers() throws Exception {
        String sudoers = Files.readString(Path.of(
            "installer", "deploy-agent", "deploy-remote.sudoers"
        ));
        String renderer = Files.readString(Path.of("installer", "deploy-agent", "sudoers-render.sh"));

        // 같은 계정을 쓰면 AI 경로가 뚫리는 순간 배포 권한까지 넘어간다.
        assertThat(sudoers).contains("__READ_SUDOERS_RULE__");
        assertThat(renderer).contains("$read_user ALL=(root) NOPASSWD: /usr/local/deploy/bin/deploy-read.sh");

        // 두 권한은 renderer 에서 각각 다른 줄로 생성하고, no-read 모드면 읽기 줄을 뺀다.
        assertThat(renderer)
            .contains("commands+=(/usr/local/deploy/bin/deploy-was-app.sh)")
            .contains("/usr/local/deploy/bin/deploy-web-app.sh")
            .contains("if [[ \"$read_enabled\" == 1 ]]; then")
            .contains("printf -v command_list '%s, ' \"${commands[@]}\"");
    }

    @Test
    void agentInstallerKeepsTheReadAccountOutOfTheDockerGroupToo() throws Exception {
        String script = Files.readString(Path.of(
            "installer", "deploy-agent", "install-deploy-agent.sh"
        ));

        // 읽기 계정에 docker 그룹을 주면 소켓으로 컨테이너를 띄울 수 있다 — 읽기 전용이 무의미해진다.
        assertThat(script)
            .contains("install_read_agent")
            .contains("gpasswd -d \"$READ_USER\" docker");
    }

    @Test
    void wasAgentCapturesTheAppLogWhenTheHealthcheckFails() throws Exception {
        String script = Files.readString(Path.of(
            "installer", "deploy-agent", "was", "deploy-was-app.sh"
        ));

        // docker run -d 는 앱 stdout 을 컨테이너 안에 가둔다. 꺼내지 않으면 배포 이력에
        // "Health check failed" 한 줄만 남고, 정작 원인(기동 실패 스택·DB 접속 거부·
        // 포트 충돌·환경변수 누락)은 어디에도 남지 않는다. 진단이 불가능해진다.
        assertThat(script)
            .contains("dump_failure_context")
            .contains("docker logs --tail \"$FAIL_LOG_LINES\"")
            .contains("docker container inspect \"$CONTAINER\"");

        // 원인 판별에 직접 쓰이는 필드들 — 특히 OOM 과 메모리 상한.
        assertThat(script)
            .contains("oomKilled=")
            .contains("exitCode=")
            .contains("restarts=")
            .contains("memLimit=");

        // 컨테이너 ID 를 버리면 실패 조사 때 붙잡을 것이 없다.
        assertThat(script).contains("Container started:");
    }

    @Test
    void wasAgentStopsTheContainerInsteadOfLeavingItCrashLooping() throws Exception {
        String script = Files.readString(Path.of(
            "installer", "deploy-agent", "was", "deploy-was-app.sh"
        ));

        // --restart unless-stopped 로 띄우므로, 기동 실패한 컨테이너는 그냥 두면
        // 무한히 죽고 뜨기를 반복하며 자원과 로그를 먹는다. 이 노드는 어차피 서비스 중이
        // 아니므로(직전 컨테이너를 이미 내렸다) 멈춰 두는 편이 낫다.
        assertThat(script).contains("재시작 루프를 막기 위해 컨테이너를 정지했습니다");

        // 단, 진단을 뽑기 전에 멈추면 로그를 놓친다 — 순서가 중요하다.
        // 줄바꿈(CRLF/LF)에 의존하지 않도록 호출 지점만 잡는다 — 첫 등장이 호출, 그 뒤가 정의다.
        int dump = script.indexOf("dump_failure_context");
        // 종료 유예와 강제 종료 판정은 stop_container에 모였다. 진단 후 호출과
        // 실제 helper의 설정된 유예 사용을 함께 검증한다.
        int stop = script.indexOf("stop_container", dump);
        assertThat(dump).isPositive();
        assertThat(stop).isGreaterThan(dump);
        String normalized = script.replace("\r\n", "\n");
        int helper = normalized.indexOf("stop_container(){");
        int helperEnd = normalized.indexOf("\n}\n", helper);
        assertThat(helper).isPositive();
        assertThat(helperEnd).isGreaterThan(helper);
        assertThat(normalized.substring(helper, helperEnd))
            .contains("docker stop --time \"$STOP_GRACE_SECONDS\" \"$CONTAINER\"")
            .contains("STOP_GRACE_EXCEEDED")
            .contains("return 42")
            .doesNotContain("docker rm");
    }

    @Test
    void staticWebAgentExplainsWhyTheHealthcheckFailed() throws Exception {
        String script = Files.readString(Path.of(
            "installer", "deploy-agent", "web", "deploy-web-static.sh"
        ));

        // 정적 배포 실패는 원인이 셋 중 하나다 — nginx 설정 오류 / current 링크가 엉뚱한 곳 /
        // 다른 server 블록이 포트를 선점. 셋을 구분할 수 있게 찍어 둔다.
        assertThat(script)
            .contains("nginx -t")
            .contains("ls -l \"$current_link\"")
            .contains("error.log");
    }
}
