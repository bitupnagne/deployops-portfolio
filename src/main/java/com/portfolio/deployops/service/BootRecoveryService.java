package com.portfolio.deployops.service;

import com.portfolio.deployops.domain.Server;
import com.portfolio.deployops.repository.ServerRepository;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.core.env.Environment;
import org.springframework.stereotype.Component;

/** Reconciles registered host services after a full OS/application restart. */
@Slf4j
@Component
@RequiredArgsConstructor
@org.springframework.boot.autoconfigure.condition.ConditionalOnProperty(name = "app.public-demo.enabled", havingValue = "false", matchIfMissing = true)
public class BootRecoveryService {
    private final ServerRepository serverRepository;
    private final SshCommandService sshCommandService;
    private final ServerService serverService;
    private final Environment environment;

    @EventListener(ApplicationReadyEvent.class)
    public void recover() {
        if (!environment.getProperty("deploy.boot-recovery.enabled", Boolean.class, true)) return;
        CompletableFuture.runAsync(this::reconcile);
    }

    void reconcile() {
        List<Server> servers = serverRepository.findAll();
        for (Server server : servers) {
            if (!server.isAutoStart() || server.getStartupService() == null) continue;
            try {
                if (!isReachable(server)) {
                    log.warn("boot recovery skipped unreachable server {}", server.getName());
                    continue;
                }
                RemoteCommandResult result = sshCommandService.execute(server, List.of(
                    "sudo", "-n", "/usr/local/deploy/bin/deploy-service-control.sh",
                    "start", server.getStartupService()));
                if (result.exitCode() != 0) {
                    log.error("boot recovery failed server={} unit={} stderr={}",
                        server.getName(), server.getStartupService(), result.stderr());
                } else {
                    log.info("boot recovery started server={} unit={}", server.getName(), server.getStartupService());
                }
                serverService.checkServer(server.getId());
            } catch (Exception e) {
                log.error("boot recovery error server={}", server.getName(), e);
            }
        }
    }

    private boolean isReachable(Server server) {
        return server.isAgentControlled()
            && Boolean.TRUE.equals(serverService.checkServer(server.getId()).get("reachable"));
    }
}
