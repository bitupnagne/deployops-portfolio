package com.portfolio.deployops.service;

import tools.jackson.databind.ObjectMapper;
import com.portfolio.deployops.config.SecurityUserDetails;
import com.portfolio.deployops.domain.*;
import com.portfolio.deployops.dto.StorageBindingForm;
import com.portfolio.deployops.dto.StorageLocationForm;
import com.portfolio.deployops.repository.*;
import java.nio.charset.StandardCharsets;
import java.util.*;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.web.server.ResponseStatusException;

@Service
@RequiredArgsConstructor
public class StorageMountService {
    public static final String HELPER="/usr/local/deploy/bin/deploy-storage.sh";
    private final JdbcTemplate jdbc;
    private final ServerRepository servers;
    private final ProjectRepository projects;
    private final DeployComponentRepository components;
    private final ProjectPermissionService permissions;
    private final SshCommandService ssh;
    private final ObjectMapper mapper;
    private final AuditLogService audit;
    private final PlatformTransactionManager transactionManager;
    private final SecretMaskingService masking=new SecretMaskingService();

    public record Binding(Long componentId,String storageKey,String relativePath,String containerPath,boolean readOnly) { }
    public record ComponentChoice(Long id,String name) { }
    public record ProjectView(boolean canManage,List<ComponentChoice> components,List<Binding> bindings,
        Map<Long,List<ServerStorageLocation>> locations,Map<Long,String> serverNames) { }
    public record Inspection(String rootPath,String mountTarget,String mountSource,String fsType) { }
    private record ServerIdentity(Long id,ServerType type,ServerEnvironment environment,ServerControlMode controlMode,
        String agentBindingId,String ipAddress,int sshPort,String deployAccount,AuthType authType) {
        static ServerIdentity of(Server server) {
            return new ServerIdentity(server.getId(),server.getServerType(),server.getEnvironment(),server.getControlMode(),
                server.getAgentBindingId(),server.getIpAddress(),server.getSshPort(),server.getDeployAccount(),server.getAuthType());
        }
    }
    private record RegistrationPlan(Server server,ServerIdentity identity,List<ServerStorageLocation> before,
        List<ServerStorageLocation> desired) { }

    public List<ServerStorageLocation> locations(Long serverId,SecurityUserDetails actor) {
        requireAdmin(actor); requireWas(serverId); return locations(serverId);
    }
    public List<ServerStorageLocation> locations(Long serverId) {
        return jdbc.query("SELECT server_id,storage_key,kind,root_path,mount_target,mount_source,fs_type,enabled FROM server_storage_location WHERE server_id=? ORDER BY storage_key",
            (r,n)->new ServerStorageLocation(r.getLong(1),r.getString(2),r.getString(3),r.getString(4),r.getString(5),r.getString(6),r.getString(7),r.getBoolean(8)),serverId);
    }
    public Inspection inspect(Long serverId,String rootPath,String kind,SecurityUserDetails actor) {
        requireAdmin(actor); StorageMountPaths.root(rootPath); validateKind(kind);
        RemoteCommandResult result=ssh.execute(requireWas(serverId),List.of("sudo",HELPER,"inspect","--root",rootPath,"--kind",kind));
        try {
            if(result.exitCode()!=0) throw new IllegalStateException();
            var value=mapper.readTree(result.stdout());
            Inspection found=new Inspection(value.path("rootPath").asText(),value.path("mountTarget").asText(),value.path("mountSource").asText(),value.path("fsType").asText());
            validateLocation(new StorageLocationForm("inspection",kind,found.rootPath(),found.mountTarget(),found.mountSource(),found.fsType(),true,null));
            if(!rootPath.equals(found.rootPath())) throw new IllegalStateException();
            return found;
        } catch(Exception e) { throw conflict("서버의 저장소 경로·마운트 정보를 확인하지 못했습니다. 에이전트 설치와 기존 OS 마운트를 확인해 주세요."); }
    }
    @Transactional(propagation = Propagation.NOT_SUPPORTED)
    public void saveLocation(Long serverId,StorageLocationForm form,SecurityUserDetails actor) {
        requireAdmin(actor); validateLocation(form);
        validateReason(form.reason());
        RegistrationPlan plan=registrationTransaction().execute(status->{
            jdbc.queryForObject("SELECT id FROM server WHERE id=? FOR UPDATE",Long.class,serverId);
            Server server=requireWas(serverId);
            List<ServerStorageLocation> before=sortedLocations(serverId);
            var replacement=new ServerStorageLocation(serverId,form.storageKey(),form.kind(),form.rootPath(),form.mountTarget(),form.mountSource(),form.fsType(),form.enabled());
            List<ServerStorageLocation> all=new ArrayList<>(before);
            all.removeIf(location->location.storageKey().equals(form.storageKey())); all.add(replacement);
            all.sort(Comparator.comparing(ServerStorageLocation::storageKey));
            if(all.size()>64) throw StorageMountPaths.invalid("서버별 허용 저장소는 64개까지 등록할 수 있습니다.");
            return new RegistrationPlan(server,ServerIdentity.of(server),before,List.copyOf(all));
        });
        // Agent enqueue commits independently and checks a server FK. Never retain the server row
        // lock while waiting for it. Recheck the inventory and host identity before publishing DB state.
        remote(plan.server(),"register",Map.of("schemaVersion",1,"serverId",serverId,"locations",plan.desired()));
        registrationTransaction().execute(status->{
            jdbc.queryForObject("SELECT id FROM server WHERE id=? FOR UPDATE",Long.class,serverId);
            if(!plan.identity().equals(ServerIdentity.of(requireWas(serverId)))||!plan.before().equals(sortedLocations(serverId)))
                throw conflict("저장소 확인 중 서버 또는 허용 저장소가 변경되었습니다. 현재 정보를 새로고침하고 다시 검토해 주세요.");
            // A local failure leaves the DB unchanged. Native and DB inventories must both match
            // before deployment; a subsequent explicit save can reconcile a completed remote change.
            jdbc.update("INSERT INTO server_storage_location(server_id,storage_key,kind,root_path,mount_target,mount_source,fs_type,enabled) VALUES(?,?,?,?,?,?,?,?) ON DUPLICATE KEY UPDATE kind=VALUES(kind),root_path=VALUES(root_path),mount_target=VALUES(mount_target),mount_source=VALUES(mount_source),fs_type=VALUES(fs_type),enabled=VALUES(enabled)",serverId,form.storageKey(),form.kind(),form.rootPath(),form.mountTarget(),form.mountSource(),form.fsType(),form.enabled());
            audit.recordCurrentRequired("STORAGE_LOCATION_UPDATE","SERVER",serverId.toString(),"storageKey="+form.storageKey()+",enabled="+form.enabled()+",reason="+masking.mask(form.reason()),AuditResult.SUCCESS);
            return null;
        });
    }
    private TransactionTemplate registrationTransaction() {
        var transaction=new TransactionTemplate(transactionManager);
        transaction.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
        return transaction;
    }
    private List<ServerStorageLocation> sortedLocations(Long serverId) {
        return locations(serverId).stream().sorted(Comparator.comparing(ServerStorageLocation::storageKey)).toList();
    }
    public ProjectView view(Long projectId,DeployEnvironment environment,SecurityUserDetails actor) {
        permissions.assertPermission(actor,projectId,ProjectPermissionType.PROJECT_VIEW);
        Project project=requireProject(projectId); List<DeployComponent> active=components.findActiveByProjectId(projectId);
        List<ComponentChoice> choices=active.stream().filter(c->c.getRole()==ServerType.WAS&&c.getArtifactType()==ArtifactType.CONTAINER)
            .map(c->new ComponentChoice(c.getId(),c.getDisplayName()==null?c.getCode():c.getDisplayName())).toList();
        if(active.isEmpty()&&project.getPipelineType()!=PipelineType.STATIC) choices=List.of(new ComponentChoice(0L,"기본 WAS 컨테이너"));
        Map<Long,List<ServerStorageLocation>> values=new LinkedHashMap<>(); Map<Long,String> names=new LinkedHashMap<>();
        for(Server server:projectServers(projectId,environment)) { values.put(server.getId(),locations(server.getId()).stream().filter(ServerStorageLocation::enabled).toList()); names.put(server.getId(),server.getName()); }
        return new ProjectView(permissions.hasPermission(actor,projectId,ProjectPermissionType.RUNTIME_CONFIG_MANAGE,environment),choices,bindings(projectId,environment),values,names);
    }
    @Transactional
    public void bind(Long projectId,DeployEnvironment environment,StorageBindingForm form,SecurityUserDetails actor) {
        permissions.assertPermission(actor,projectId,ProjectPermissionType.RUNTIME_CONFIG_MANAGE,environment);
        Project project=requireProject(projectId); long componentId=form.componentId()==null?0:form.componentId();
        List<DeployComponent> active=components.findActiveByProjectId(projectId);
        DeployComponent component=componentId==0?null:active.stream().filter(c->Objects.equals(c.getId(),componentId)&&c.getRole()==ServerType.WAS&&c.getArtifactType()==ArtifactType.CONTAINER).findFirst().orElseThrow(()->StorageMountPaths.invalid("이 프로젝트의 활성 WAS 컨테이너를 선택해 주세요."));
        if(componentId==0&&(!active.isEmpty()||project.getPipelineType()==PipelineType.STATIC)) throw StorageMountPaths.invalid("명시된 WAS 배포 단위를 선택해 주세요.");
        validateReason(form.reason());
        List<StorageBindingForm.Mount> mounts=form.mounts();
        if(mounts==null||mounts.size()>16) throw StorageMountPaths.invalid("마운트 목록은 배포 단위별 최대 16개입니다.");
        validateBindingPaths(mounts);
        if(!mounts.isEmpty()) {
            validateContainer(project,component);
            List<Server> targets=projectServers(projectId,environment);
            if(targets.isEmpty()) throw conflict("선택 환경에 연결된 WAS 서버가 없습니다.");
            for(var binding:mounts) for(var target:targets) requireLocation(target.getId(),binding.storageKey());
        }
        jdbc.queryForObject("SELECT id FROM project WHERE id=? FOR UPDATE",Long.class,projectId);
        jdbc.update("DELETE FROM project_storage_binding WHERE project_id=? AND environment=? AND component_id=?",projectId,environment.name(),componentId);
        for(var binding:mounts) jdbc.update("INSERT INTO project_storage_binding(project_id,environment,component_id,storage_key,relative_path,container_path,read_only) VALUES(?,?,?,?,?,?,?)",projectId,environment.name(),componentId,binding.storageKey(),binding.relativePath(),binding.containerPath(),binding.readOnly());
        audit.recordCurrentRequired("STORAGE_BINDING_UPDATE","PROJECT",projectId.toString(),"environment="+environment+",component="+componentId+",mounts="+mounts.size()+",reason="+masking.mask(form.reason()),AuditResult.SUCCESS);
    }
    public List<Binding> bindings(Long projectId,DeployEnvironment env) {
        return jdbc.query("SELECT component_id,storage_key,relative_path,container_path,read_only FROM project_storage_binding WHERE project_id=? AND environment=? ORDER BY component_id,container_path",
            (r,n)->new Binding(r.getLong(1),r.getString(2),r.getString(3),r.getString(4),r.getBoolean(5)),projectId,env.name());
    }
    /** Does not contact remote hosts while hashing a plan. Deployment preflight performs actual I/O. */
    public List<StorageMountSettings> describe(Project project,DeployEnvironment env,List<ServerGroup> groups,List<DeployComponent> active,DeployScope scope) {
        List<Binding> bindings=bindings(project.getId(),env); if(bindings.isEmpty()) return null;
        List<Server> targets=EffectiveDeployTargets.resolve(Project.builder().serverGroups(groups).build(),env,scope).stream().filter(s->s.getServerType()==ServerType.WAS).toList();
        if(targets.isEmpty()) return null;
        List<StorageMountSettings> result=new ArrayList<>();
        for(Binding binding:bindings) {
            DeployComponent component=binding.componentId()==0?null:active.stream().filter(c->Objects.equals(c.getId(),binding.componentId())&&c.getRole()==ServerType.WAS&&c.getArtifactType()==ArtifactType.CONTAINER).findFirst().orElseThrow(()->conflict("저장소 연결의 WAS 배포 단위가 변경되었습니다. 저장소 설정을 정리해 주세요."));
            if(binding.componentId()==0&&!active.isEmpty()) throw conflict("기본 저장소 연결을 활성 WAS 배포 단위에 다시 연결해 주세요.");
            validateContainer(project,component);
            for(Server target:targets) {
                var location=requireLocation(target.getId(),binding.storageKey());
                result.add(new StorageMountSettings(target.getId(),binding.componentId(),binding.storageKey(),location.kind(),location.rootPath(),binding.relativePath(),binding.containerPath(),binding.readOnly(),location.mountTarget(),location.mountSource(),location.fsType()));
            }
        }
        result.sort(Comparator.comparing(StorageMountSettings::serverId).thenComparing(StorageMountSettings::componentId).thenComparing(StorageMountSettings::containerPath));
        return result.isEmpty()?null:List.copyOf(result);
    }
    public void assertPinnedAllowed(List<StorageMountSettings> settings) {
        if(settings==null) return;
        for(var mount:settings) {
            var location=requireLocation(mount.serverId(),mount.storageKey());
            if(!Objects.equals(location.kind(),mount.kind())||!Objects.equals(location.rootPath(),mount.rootPath())||!Objects.equals(location.mountTarget(),mount.mountTarget())||!Objects.equals(location.mountSource(),mount.mountSource())||!Objects.equals(location.fsType(),mount.fsType())) throw conflict("승인된 저장소와 현재 WAS 허용 저장소가 다릅니다. 새 요청으로 검토해 주세요.");
        }
    }
    public static List<StorageMountSettings> pinnedFor(Project project,Server server,Long componentId) {
        if(project.getStorageMounts()==null) return List.of();
        long id=componentId==null?0:componentId;
        return project.getStorageMounts().stream().filter(m->Objects.equals(m.serverId(),server.getId())&&Objects.equals(m.componentId(),id)).toList();
    }
    public static Map<String,Object> spec(Long projectId,DeployEnvironment env,Long componentId,Server server,Long requestId,String containerName,String user,List<StorageMountSettings> mounts) {
        List<Map<String,Object>> values=mounts.stream().map(m->{
            Map<String,Object> value=new LinkedHashMap<>();
            value.put("storageKey",m.storageKey()); value.put("kind",m.kind()); value.put("rootPath",m.rootPath());
            value.put("relativePath",m.relativePath()); value.put("containerPath",m.containerPath()); value.put("readOnly",m.readOnly());
            value.put("mountTarget",m.mountTarget()); value.put("mountSource",m.mountSource()); value.put("fsType",m.fsType());
            return value;
        }).toList();
        Map<String,Object> result=new LinkedHashMap<>();
        result.put("schemaVersion",1); result.put("projectId",projectId); result.put("environment",env.name());
        result.put("componentId",componentId==null?0:componentId); result.put("serverId",server.getId());
        result.put("requestId",requestId); result.put("containerName",containerName); result.put("containerUser",user); result.put("mounts",values);
        return result;
    }
    public void checkBinding(Long projectId,DeployEnvironment env,Long componentId,Long serverId,SecurityUserDetails actor) {
        permissions.assertPermission(actor,projectId,ProjectPermissionType.RUNTIME_CONFIG_MANAGE,env);
        Project project=requireProject(projectId); long id=componentId==null?0:componentId;
        List<DeployComponent> active=components.findActiveByProjectId(projectId);
        DeployComponent component=id==0?null:active.stream().filter(c->Objects.equals(c.getId(),id)&&c.getRole()==ServerType.WAS&&c.getArtifactType()==ArtifactType.CONTAINER).findFirst().orElseThrow(()->StorageMountPaths.invalid("WAS 배포 단위를 선택해 주세요."));
        if(id==0&&(!active.isEmpty()||project.getPipelineType()==PipelineType.STATIC)) throw StorageMountPaths.invalid("WAS 배포 단위를 선택해 주세요.");
        Server server=projectServers(projectId,env).stream().filter(s->Objects.equals(s.getId(),serverId)).findFirst().orElseThrow(()->StorageMountPaths.invalid("이 프로젝트와 환경에 연결된 WAS 서버를 선택해 주세요."));
        validateContainer(project,component);
        List<StorageMountSettings> selected=new ArrayList<>();
        for(var b:bindings(projectId,env)) if(Objects.equals(b.componentId(),id)) {
            var l=requireLocation(serverId,b.storageKey());
            selected.add(new StorageMountSettings(serverId,id,b.storageKey(),l.kind(),l.rootPath(),b.relativePath(),b.containerPath(),b.readOnly(),l.mountTarget(),l.mountSource(),l.fsType()));
        }
        if(selected.isEmpty()) throw StorageMountPaths.invalid("먼저 저장소 연결을 저장해 주세요.");
        String user=component!=null&&component.getContainerUser()!=null?component.getContainerUser():project.getContainerUser();
        remote(server,"check",spec(projectId,env,id,server,0L,"storage-check",user,selected));
    }
    public static void validateBindingPaths(List<StorageBindingForm.Mount> mounts) {
        for(int i=0;i<mounts.size();i++) {
            var mount=mounts.get(i); if(mount==null) throw StorageMountPaths.invalid("마운트 항목을 확인해 주세요.");
            StorageMountPaths.key(mount.storageKey()); StorageMountPaths.relative(mount.relativePath()); StorageMountPaths.target(mount.containerPath());
            for(int j=0;j<i;j++) if(StorageMountPaths.overlaps(mount.containerPath(),mounts.get(j).containerPath())) throw StorageMountPaths.invalid("컨테이너 마운트 경로는 서로 중첩할 수 없습니다.");
        }
    }
    public static void validateContainer(Project project,DeployComponent component) {
        String user=component!=null&&component.getContainerUser()!=null?component.getContainerUser():project.getContainerUser();
        String caps=component!=null&&component.getContainerCapAdd()!=null?component.getContainerCapAdd():project.getContainerCapAdd();
        if(user==null||!user.matches("[1-9][0-9]{0,8}:[1-9][0-9]{0,8}")||project.isContainerPrivileged()||component!=null&&component.isContainerPrivileged()||caps!=null&&!caps.isBlank()) throw conflict("저장소 연결에는 root가 아닌 숫자 UID:GID, 비특권 컨테이너와 추가 capability 없음이 필요합니다.");
    }
    private void remote(Server server,String action,Object value) {
        try {
            byte[] body=mapper.writeValueAsBytes(value);
            if(body.length>65536) throw new IllegalArgumentException();
            if(ssh.executeSensitive(server,List.of("sudo",HELPER,action),body).exitCode()!=0) throw new IllegalStateException();
        } catch(Exception e) {
            if ("check".equals(action)) {
                throw conflict("저장된 연결의 경로·권한 검사를 완료하지 못했습니다. 배포 단위의 숫자 UID:GID로 경로를 읽고 탐색할 수 있는지, 읽기·쓰기(rw) 연결이면 파일 생성·쓰기·삭제가 가능한지 확인해 주세요. "
                    + "상위 경로의 소유권·권한, 등록된 OS/NAS 마운트 상태와 에이전트 연결도 확인한 뒤 검사를 다시 실행해 주세요. 저장된 연결은 변경하지 않았습니다.");
            }
            throw conflict("원격 저장소 검증·등록에 실패했습니다. OS 마운트와 배포 에이전트를 확인한 뒤 다시 저장해 주세요.");
        }
    }
    private List<Server> projectServers(Long projectId,DeployEnvironment environment) {
        ServerEnvironment env=environment==DeployEnvironment.PROD?ServerEnvironment.PROD:ServerEnvironment.TEST;
        return projects.findServerGroupsByProjectId(projectId).stream().filter(g->g.getEnvironment()==env).flatMap(g->servers.findByServerGroupId(g.getId()).stream()).filter(s->s.getServerType()==ServerType.WAS).sorted(Comparator.comparing(Server::getId)).toList();
    }
    private ServerStorageLocation requireLocation(Long serverId,String key) { return locations(serverId).stream().filter(s->s.enabled()&&s.storageKey().equals(key)).findFirst().orElseThrow(()->conflict("WAS #"+serverId+"에 활성 허용 저장소 '"+key+"'가 없습니다.")); }
    private Server requireWas(Long id) { Server server=servers.findById(id).orElseThrow(()->new ResponseStatusException(HttpStatus.NOT_FOUND,"서버를 찾을 수 없습니다.")); if(server.getServerType()!=ServerType.WAS) throw StorageMountPaths.invalid("허용 저장소는 WAS 서버에 등록합니다."); return server; }
    private Project requireProject(Long id) { return projects.findById(id).orElseThrow(()->new ResponseStatusException(HttpStatus.NOT_FOUND,"프로젝트를 찾을 수 없습니다.")); }
    private static void requireAdmin(SecurityUserDetails actor) { if(actor==null||!"SUPER_ADMIN".equals(actor.getRole())) throw new ResponseStatusException(HttpStatus.FORBIDDEN,"WAS 허용 저장소 등록은 최고 관리자만 가능합니다."); }
    private static void validateKind(String kind) { if(!Set.of("LOCAL","NAS").contains(Objects.toString(kind,""))) throw StorageMountPaths.invalid("LOCAL 또는 NAS 저장소를 선택해 주세요."); }
    private static void validateReason(String reason) { if(reason==null||reason.isBlank()||reason.length()>400||reason.chars().anyMatch(c->c<32||c==127)) throw StorageMountPaths.invalid("변경 사유를 줄바꿈 없이 400자 이내로 입력해 주세요."); }
    private static void validateLocation(StorageLocationForm form) {
        if(form==null) throw StorageMountPaths.invalid("저장소 설정을 입력해 주세요.");
        StorageMountPaths.key(form.storageKey()); StorageMountPaths.root(form.rootPath()); validateKind(form.kind());
        if(!"/".equals(form.mountTarget())) StorageMountPaths.absolute(form.mountTarget());
        if(form.mountTarget()==null||!(form.rootPath().equals(form.mountTarget())||form.rootPath().startsWith(form.mountTarget().equals("/")?"/":form.mountTarget()+"/"))) throw StorageMountPaths.invalid("저장소 경로가 확인된 마운트 내부에 있어야 합니다.");
        if(form.mountSource()==null||form.mountSource().isBlank()||form.mountSource().length()>512||form.mountSource().chars().anyMatch(c->c<33||c>126)||form.fsType()==null||!form.fsType().matches("[a-zA-Z0-9_.-]{1,32}")) throw StorageMountPaths.invalid("마운트 원본과 파일시스템 종류를 서버에서 확인해 주세요.");
        boolean network=Set.of("nfs","nfs4","cifs","smb3","fuse.sshfs","ceph","glusterfs").contains(form.fsType());
        if(("NAS".equals(form.kind()))!=network) throw StorageMountPaths.invalid("저장소 종류와 실제 파일시스템이 다릅니다.");
    }
    private static ResponseStatusException conflict(String message) { return new ResponseStatusException(HttpStatus.CONFLICT,message); }
}
