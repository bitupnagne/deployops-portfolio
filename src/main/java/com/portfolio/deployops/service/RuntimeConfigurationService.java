package com.portfolio.deployops.service;

import com.portfolio.deployops.config.SecurityUserDetails;
import com.portfolio.deployops.domain.*;
import com.portfolio.deployops.dto.RuntimeBindingForm;
import com.portfolio.deployops.dto.RuntimeRevisionForm;
import com.portfolio.deployops.repository.DeployComponentRepository;
import com.portfolio.deployops.repository.ProjectRepository;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.*;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.support.GeneratedKeyHolder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

@Service
@RequiredArgsConstructor
public class RuntimeConfigurationService {
    private final JdbcTemplate jdbc;
    private final ProjectRepository projects;
    private final DeployComponentRepository components;
    private final ProjectPermissionService permissions;
    private final SecretCryptoService crypto;
    private final AuditLogService audit;

    public record RevisionView(Long id, Long componentId, List<String> keyNames, String createdAt) { }
    public record View(boolean canManage, boolean canReadLogs, List<RevisionView> revisions,
                       List<ManagedRuntimeSettings> bindings) { }

    @Transactional(readOnly = true)
    public View view(Long projectId, DeployEnvironment env, SecurityUserDetails actor) {
        permissions.assertPermission(actor, projectId, ProjectPermissionType.PROJECT_VIEW);
        requireProject(projectId);
        List<RevisionView> revisions = jdbc.query("SELECT id,component_id,key_names,created_at FROM runtime_env_revisions WHERE project_id=? AND environment=? ORDER BY id DESC",
            (rs,n) -> new RevisionView(rs.getLong(1),rs.getLong(2),List.of(rs.getString(3).split(",")),rs.getString(4)),projectId,env.name());
        return new View(permissions.hasPermission(actor,projectId,ProjectPermissionType.RUNTIME_CONFIG_MANAGE,env),
            permissions.hasPermission(actor,projectId,ProjectPermissionType.RUNTIME_LOG_READ,env),revisions,bindings(projectId,env));
    }

    @Transactional
    public RevisionView create(Long projectId, DeployEnvironment env, RuntimeRevisionForm form, SecurityUserDetails actor) {
        permissions.assertPermission(actor,projectId,ProjectPermissionType.RUNTIME_CONFIG_MANAGE,env);
        long component = scope(projectId,form.componentId());
        reason(form.reason());
        List<String> names = validateContent(form.content());
        String encrypted = crypto.encryptIfPlain(form.content());
        String encryptedReason=crypto.encryptIfPlain(form.reason());
        String fingerprint = fingerprint(encrypted);
        GeneratedKeyHolder key = new GeneratedKeyHolder();
        jdbc.update(connection -> {
            var statement = connection.prepareStatement("INSERT INTO runtime_env_revisions(project_id,environment,component_id,encrypted_payload,record_fingerprint,key_names,creator_id,encrypted_reason) VALUES(?,?,?,?,?,?,?,?)",java.sql.Statement.RETURN_GENERATED_KEYS);
            statement.setLong(1,projectId); statement.setString(2,env.name()); statement.setLong(3,component);
            statement.setString(4,encrypted); statement.setString(5,fingerprint); statement.setString(6,String.join(",",names)); statement.setLong(7,actor.getId());
            statement.setString(8,encryptedReason);
            return statement;
        },key);
        Long id = Objects.requireNonNull(key.getKey()).longValue();
        audit.recordCurrentRequired("RUNTIME_REVISION_CREATE","PROJECT",projectId.toString(),"environment="+env+",component="+component+",revision="+id,AuditResult.SUCCESS);
        return new RevisionView(id,component,names,null);
    }

    @Transactional
    public void bind(Long projectId, DeployEnvironment env, RuntimeBindingForm form, SecurityUserDetails actor) {
        permissions.assertPermission(actor,projectId,ProjectPermissionType.RUNTIME_CONFIG_MANAGE,env);
        long component=scope(projectId,form.componentId()); reason(form.reason());
        if (!Set.of("INHERIT","MANAGED_REVISION","NONE").contains(Objects.toString(form.mode(),""))
            || component==0 && "INHERIT".equals(form.mode()) || form.tmpMiB()!=0 && (form.tmpMiB()<16 || form.tmpMiB()>512)) throw bad();
        if ("MANAGED_REVISION".equals(form.mode())) {
            if(form.revisionId()==null || jdbc.queryForObject("SELECT COUNT(*) FROM runtime_env_revisions WHERE id=? AND project_id=? AND environment=? AND component_id=?",Integer.class,form.revisionId(),projectId,env.name(),component)!=1) throw bad();
        } else if(form.revisionId()!=null || "INHERIT".equals(form.mode()) && (form.persistentLogs() || form.tmpMiB()!=0)) throw bad();
        int stopGrace;
        try { stopGrace=StopGrace.seconds(form.stopGraceSeconds()); }
        catch (IllegalArgumentException error) { throw new ResponseStatusException(HttpStatus.BAD_REQUEST,error.getMessage()); }
        if ("INHERIT".equals(form.mode()) && form.stopGraceSeconds()!=null) throw bad();
        Project project=requireProject(projectId);
        if("MANAGED_REVISION".equals(form.mode()) && project.getEnvFilePath()!=null && !project.getEnvFilePath().isBlank()) throw new ResponseStatusException(HttpStatus.BAD_REQUEST,"기존 환경 파일 경로를 먼저 해제해 주세요.");
        jdbc.update("INSERT INTO runtime_bindings(project_id,environment,component_id,mode,revision_id,persistent_logs,tmp_mib,encrypted_reason,stop_grace_seconds) VALUES(?,?,?,?,?,?,?,?,?) ON DUPLICATE KEY UPDATE mode=VALUES(mode),revision_id=VALUES(revision_id),persistent_logs=VALUES(persistent_logs),tmp_mib=VALUES(tmp_mib),encrypted_reason=VALUES(encrypted_reason),stop_grace_seconds=VALUES(stop_grace_seconds)",projectId,env.name(),component,form.mode(),form.revisionId(),form.persistentLogs(),form.tmpMiB(),crypto.encryptIfPlain(form.reason()),stopGrace);
        audit.recordCurrentRequired("RUNTIME_BINDING_UPDATE","PROJECT",projectId.toString(),"environment="+env+",component="+component+",mode="+form.mode()+",revision="+form.revisionId()+",stopGraceSeconds="+stopGrace,AuditResult.SUCCESS);
    }

    public List<ManagedRuntimeSettings> bindings(Long projectId, DeployEnvironment env) {
        return jdbc.query("SELECT b.component_id,b.mode,b.revision_id,r.record_fingerprint,b.persistent_logs,b.tmp_mib,b.stop_grace_seconds FROM runtime_bindings b LEFT JOIN runtime_env_revisions r ON r.id=b.revision_id WHERE b.project_id=? AND b.environment=? ORDER BY b.component_id",
            (rs,n)->new ManagedRuntimeSettings(rs.getLong(1),rs.getString(2),rs.getObject(3,Long.class),rs.getString(4),rs.getBoolean(5),rs.getInt(6),rs.getInt(7)),projectId,env.name());
    }

    public String decryptPinned(Long projectId, DeployEnvironment env, ManagedRuntimeSettings selected) {
        if(selected==null || selected.revisionId()==null) throw bad();
        List<String> values=jdbc.query("SELECT encrypted_payload FROM runtime_env_revisions WHERE id=? AND project_id=? AND environment=? AND record_fingerprint=?",
            (rs,n)->rs.getString(1),selected.revisionId(),projectId,env.name(),selected.recordFingerprint());
        if(values.size()!=1 || !fingerprint(values.getFirst()).equals(selected.recordFingerprint())) throw new RemoteExecutionException("고정된 런타임 설정을 확인할 수 없습니다.");
        try { String content=crypto.decryptIfEncrypted(values.getFirst()); validateContent(content); return content; }
        catch(Exception ignored) { throw new RemoteExecutionException("고정된 런타임 설정을 복호화할 수 없습니다."); }
    }

    public static ManagedRuntimeSettings resolve(List<ManagedRuntimeSettings> settings, Long componentId) {
        if(settings==null || settings.isEmpty()) return null;
        ManagedRuntimeSettings base=settings.stream().filter(s->s.componentId()==0).findFirst().orElse(null);
        ManagedRuntimeSettings override=settings.stream().filter(s->Objects.equals(s.componentId(),componentId)).findFirst().orElse(null);
        if(override==null || "INHERIT".equals(override.mode())) return base;
        return override;
    }
    public static List<String> validateContent(String content) {
        if(content==null || content.isBlank() || content.length()>65536 || !StandardCharsets.UTF_8.newEncoder().canEncode(content)
            || content.getBytes(StandardCharsets.UTF_8).length>65536 || content.indexOf('\r')>=0) throw bad();
        TreeSet<String> keys=new TreeSet<>();
        for(String line:content.split("\n",-1)) {
            if(line.isEmpty()) continue;
            int separator=line.indexOf('=');
            if(separator<1 || !line.substring(0,separator).matches("[A-Za-z_][A-Za-z0-9_]{0,127}") || !keys.add(line.substring(0,separator)) || line.chars().anyMatch(c->c<32 || c==127)) throw bad();
        }
        if(keys.isEmpty()) throw bad(); return List.copyOf(keys);
    }
    private long scope(Long projectId, Long componentId) {
        Project project=requireProject(projectId); long value=componentId==null?0:componentId;
        if(value<0) throw bad();
        List<DeployComponent> active=components.findActiveByProjectId(projectId);
        if(value==0 && (project.getPipelineType()==PipelineType.STATIC || !active.isEmpty()) && active.stream().noneMatch(c->c.getArtifactType()==ArtifactType.CONTAINER)) throw bad();
        if(value!=0 && components.findActiveByProjectId(projectId).stream().noneMatch(c->Objects.equals(c.getId(),value) && c.getArtifactType()==ArtifactType.CONTAINER)) throw bad();
        return value;
    }
    private Project requireProject(Long id) { return projects.findById(id).orElseThrow(()->new ResponseStatusException(HttpStatus.NOT_FOUND,"프로젝트를 찾을 수 없습니다.")); }
    private static void reason(String reason) { if(reason==null || reason.isBlank() || reason.length()>400) throw new ResponseStatusException(HttpStatus.BAD_REQUEST,"변경 사유를 400자 이내로 입력해 주세요."); }
    public static String fingerprint(String value) { try{return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(value.getBytes(StandardCharsets.UTF_8)));}catch(Exception e){throw new IllegalStateException("설정 무결성 검사를 수행할 수 없습니다.");} }
    private static ResponseStatusException bad() { return new ResponseStatusException(HttpStatus.BAD_REQUEST,"런타임 설정 형식과 적용 범위를 확인해 주세요."); }
}
