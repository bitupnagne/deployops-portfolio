package com.portfolio.deployops.service;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;

/**
 * 설치기 컴포넌트의 리소스 튜닝·운영 안전장치를 검증한다.
 *
 * <p>여기서 잡는 회귀는 "설치는 되는데 서버 스펙 산정이 틀어지는" 부류다. 기본값으로 되돌아가면
 * 호스트 RAM 요구가 노드당 4~8GB 늘어난다(Nexus 힙 2703m·GitLab omnibus 기본).
 * 설치를 실제로 돌려 봐야 알 수 있는 부류라, 스크립트 내용으로 고정해 둔다.
 */
class InstallerResourceTuningTest {

    private static String component(String name) throws Exception {
        return Files.readString(Path.of("installer", "components", name));
    }

    @Test
    void nexusPinsHeapInsteadOfImageDefault() throws Exception {
        String script = component("nexus.sh");

        // 이미지 기본값은 -Xmx2703m + MaxDirectMemorySize 2703m 라 상시 5~6GB 를 잡는다.
        assertThat(script)
            .contains("NEXUS_HEAP")
            .contains("INSTALL4J_ADD_VM_PARAMS=")
            .contains("-Xms${heap} -Xmx${heap} -XX:MaxDirectMemorySize=${heap}");
    }

    @Test
    void gitlabPinsPumaAndSidekiqAndDisablesPrometheusByDefault() throws Exception {
        String script = component("gitlab.sh");

        assertThat(script)
            .contains("GITLAB_PUMA_WORKERS")
            .contains("GITLAB_SIDEKIQ_CONCURRENCY")
            .contains("puma_workers=\"${GITLAB_PUMA_WORKERS:-2}\"")
            .contains("sidekiq_conc=\"${GITLAB_SIDEKIQ_CONCURRENCY:-5}\"")
            .contains("prometheus=\"${GITLAB_PROMETHEUS:-n}\"")
            .contains("gitlab_expected_omnibus_config")
            .contains("gitlab_omnibus_config_matches")
            .contains("puma['worker_processes'] = %s;")
            .contains("sidekiq['concurrency'] = %s;")
            .contains("prometheus_monitoring['enable'] = %s;")
            .contains("[[ \"$actual\" == \"$expected\" ]]")
            .doesNotContain("[[ \"$omnibus\" == *");
    }

    @Test
    void appServicesPinMaxHeapSoUsageDoesNotFollowHostSize() throws Exception {
        // -Xmx 미지정이면 JVM 기본값(물리 RAM 의 1/4)이라 16GB 호스트에서 힙 4GB 를 잡는다.
        assertThat(component("deploy-system.sh"))
            .contains("DEPLOYOPS_HEAP")
            .contains("-Xms512m -Xmx$heap -XX:+ExitOnOutOfMemoryError -jar");

        assertThat(component("worksystem-server.sh"))
            .contains("WORKSYSTEM_HEAP")
            .contains("-Xms512m -Xmx$heap -XX:+ExitOnOutOfMemoryError -jar");
    }

    @Test
    void deploySystemNoLongerProvisionsAMailbox() throws Exception {
        String script = component("deploy-system.sh");

        // 3망 구조에서 배포망은 대상 서버에 직접 SSH 로 닿는다. 우편함을 만들 이유가 없고,
        // 남겨 두면 아무도 읽지 않는 디렉터리를 앱 계정 소유로 깔아 두는 셈이다.
        assertThat(script)
            .doesNotContain("MAILBOX")
            .doesNotContain("mailbox_dir")
            .doesNotContain("relay");
    }

    @Test
    void nginxSupportsCustomProxyTargetsBeyondTheFiveBuiltIns() throws Exception {
        String script = component("nginx.sh");

        // 내장 5종(OFFICE/DEPLOY/GITLAB/NEXUS_WEB/NEXUS_REGISTRY)은 이 저장소가 배포하는
        // 서비스뿐이라, 업무 앱을 DMZ 로 노출하려면 별도 선언 경로가 있어야 한다.
        assertThat(script)
            .contains("NGINX_CUSTOM_SERVICES")
            .contains("_nginx_server_block \"$c_name\" \"$c_lport\" \"$c_host\" \"$c_uport\"");
    }

    @Test
    void nginxServesTheDeployedStaticReleaseNotTheDistroDefaultPage() throws Exception {
        String script = component("nginx.sh");

        // 배포 에이전트는 <web-root>/current 심볼릭 링크를 돌린다. nginx 가 그걸 가리키지 않으면
        // 배포는 "성공"하는데 아무것도 서비스되지 않는다.
        assertThat(script)
            .contains("NGINX_STATIC_ROOT")
            .contains("root ${root}/current;");

        // 더 위험한 건 배포판 기본 server 블록이다. conf.d 는 알파벳 순으로 로드돼
        // default.conf 가 80 을 선점하고, 그 기본 페이지도 200 이라 헬스체크가 통과한다.
        // 즉 배포가 실패해도 아무도 눈치채지 못한다.
        assertThat(script)
            .contains("_nginx_disable_stock_default")
            .contains("disabled-by-installer");
    }

    @Test
    void installerScriptsCarryNoStrayControlCharacters() throws Exception {
        // 정규식에 단어경계(\\b)를 쓰려다 백스페이스(0x08)가 파일에 박히는 사고가 있었다.
        // 눈에 보이지 않고 bash -n 도 통과하며, 그 조건문이 조용히 항상 거짓이 된다.
        for (String name : new String[]{"nginx.sh", "docker.sh", "deploy-system.sh", "deploy-agent.sh"}) {
            assertThat(component(name))
                .as("%s 에 제어문자가 섞였다", name)
                .doesNotContain("\b")
                .doesNotContain("\f")
                .doesNotContain("\u001b");
        }
    }

    @Test
    void installerAsksForTheServerRoleBeforeInstallingAnything() throws Exception {
        String script = java.nio.file.Files.readString(
            java.nio.file.Path.of("installer", "install.sh"));

        // 역할 선택이 없으면 처음 설치하는 사람은 "전체 설치"를 누른다. 그러면 DMZ 에
        // docker·GitLab·DB 까지 깔려 3망 설계가 첫 삽부터 무너진다.
        assertThat(script)
            .contains("ROLES=(")
            .contains("print_role_menu")
            .contains("run_role");

        // 역할별 컴포넌트 조합이 설계와 맞아야 한다.
        assertThat(script)
            .contains("docker,mariadb,nexus,deploy-system")   // 배포망 deploy01
            .contains("docker,deploy-agent")                  // 대상 WAS
            .contains("nginx,deploy-agent");                  // 대상 WEB — docker 없음

        // "전체 설치" 경로는 없어야 한다.
        assertThat(script).doesNotContain("run_all_in_order");
    }

    @Test
    void statusTableDistinguishesConfiguredFromMerelyInstalled() throws Exception {
        // 패키지만 깔린 상태를 installed 로 보고하면, 구성이 하나도 없는데도
        // 처음 설치하는 사람이 "설치됨"을 보고 그냥 넘어간다.
        assertThat(component("nginx.sh"))
            .contains("echo partial");

        // 설치된 타입의 필수 파일과 현재 배포본을 검사한 뒤 실제 타입을 표시한다.
        // WEB 서버에 WAS 에이전트만 깔린 상태를 설치 완료로 오인하면 안 된다.
        assertThat(component("deploy-agent.sh"))
            .contains("case \"$type\" in was|web|both)")
            .contains("if [[ \"$type\" == was || \"$type\" == both ]]")
            .contains("if [[ \"$type\" == web || \"$type\" == both ]]")
            .contains("drift(agent-version:$type)")
            .contains("echo \"installed($type)\"");
    }

    @Test
    void agentHealthDefaultsFollowTheAgentType() throws Exception {
        // WEB 노드에 WAS 기본값(8080 /actuator/health)이 들어가면 배포는 성공하는데
        // 헬스체크가 실패해 매번 직전 릴리스로 롤백된다.
        assertThat(component("deploy-agent.sh"))
            .contains("if [[ \"$atype\" == \"web\" ]]; then d_hport=\"80\"; d_hpath=\"/\"; fi");
    }

    @Test
    void webAndNginxAgentsSerializeConcurrentChanges() throws Exception {
        assertThat(java.nio.file.Files.readString(java.nio.file.Path.of(
            "installer", "deploy-agent", "web", "deploy-web-static.sh")))
            .contains("flock -n 9")
            .contains("deploy-ops-web-static.lock");
        assertThat(java.nio.file.Files.readString(java.nio.file.Path.of(
            "installer", "deploy-agent", "web", "deploy-nginx-policy.sh")))
            .contains("flock -n 9")
            .contains("deploy-ops-nginx-policy.lock");
    }
}
