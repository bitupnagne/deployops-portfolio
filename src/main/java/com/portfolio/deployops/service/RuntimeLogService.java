package com.portfolio.deployops.service;

import com.portfolio.deployops.config.SecurityUserDetails;
import com.portfolio.deployops.domain.*;
import com.portfolio.deployops.repository.*;
import java.util.*;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.web.server.ResponseStatusException;
import tools.jackson.databind.ObjectMapper;

@Service
@RequiredArgsConstructor
public class RuntimeLogService {
    private final ProjectPermissionService permissions;
    private final ProjectRepository projects;
    private final ServerRepository servers;
    private final DeployComponentRepository components;
    private final RuntimeConfigurationService configuration;
    private final SshCommandService ssh;
    private final ObjectMapper mapper;
    private final AuditLogService audit;
    public record LogView(String text, boolean truncated) { }
    public LogView read(Long projectId, DeployEnvironment env, Long serverId, Long componentId, int lines, SecurityUserDetails actor) {
        permissions.assertPermission(actor,projectId,ProjectPermissionType.PROJECT_VIEW);
        permissions.assertPermission(actor,projectId,ProjectPermissionType.RUNTIME_LOG_READ,env);
        if(lines<1 || lines>1000) throw bad();
        Project project=projects.findById(projectId).orElseThrow(RuntimeLogService::bad);
        Server server=projects.findServerGroupsByProjectId(projectId).stream()
            .filter(g->g.getEnvironment().name().equals(env.name()))
            .flatMap(g->servers.findByServerGroupId(g.getId()).stream()).filter(s->Objects.equals(s.getId(),serverId) && s.getEnvironment().name().equals(env.name())).findFirst().orElseThrow(RuntimeLogService::bad);
        List<DeployComponent> candidates=components.findActiveByProjectId(projectId).stream().filter(c->c.getRole()==server.getServerType()).toList();
        if(candidates.size()>1) throw bad();
        DeployComponent component=candidates.isEmpty()?null:candidates.getFirst();
        long id=component==null?0:component.getId();
        if(!Objects.equals(componentId,id) || component!=null && component.getArtifactType()!=ArtifactType.CONTAINER) throw bad();
        var selected=RuntimeConfigurationService.resolve(configuration.bindings(projectId,env),id);
        if(selected==null || !selected.persistentLogs()) throw bad();
        String user=component==null || component.getContainerUser()==null?project.getContainerUser():component.getContainerUser();
        if(user==null || !user.matches("[1-9][0-9]{0,9}:[1-9][0-9]{0,9}")) throw bad();
        String[] identity=user.split(":");
        List<String> args=new ArrayList<>();
        if(!"root".equals(server.getDeployAccount())) args.addAll(List.of("sudo","-n"));
        args.addAll(List.of("/usr/local/deploy/bin/deploy-runtime.sh","logs","--project",projectId.toString(),"--environment",env.name(),"--component",Long.toString(id),"--uid",identity[0],"--gid",identity[1],"--lines",Integer.toString(lines)));
        var result=ssh.execute(server,args);
        if(result.exitCode()!=0 || result.stdout().getBytes(java.nio.charset.StandardCharsets.UTF_8).length>1572864) throw new RemoteExecutionException("애플리케이션 파일 로그를 조회할 수 없습니다.");
        LogView output;
        try { output=mapper.readValue(result.stdout(),LogView.class); if(output.text()==null || output.text().getBytes(java.nio.charset.StandardCharsets.UTF_8).length>262144) throw bad(); }
        catch(Exception ignored) { throw new RemoteExecutionException("애플리케이션 파일 로그 응답을 확인할 수 없습니다."); }
        audit.recordCurrentRequired("RUNTIME_LOG_READ","PROJECT",projectId.toString(),"environment="+env+",server="+serverId+",component="+id,AuditResult.SUCCESS);
        return output;
    }
    private static ResponseStatusException bad(){return new ResponseStatusException(HttpStatus.BAD_REQUEST,"프로젝트에 연결된 서버와 파일 로그 설정을 확인해 주세요.");}
}
