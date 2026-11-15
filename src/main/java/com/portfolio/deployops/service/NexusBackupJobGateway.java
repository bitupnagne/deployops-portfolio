package com.portfolio.deployops.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.IOException;
import java.nio.file.*;
import java.nio.file.attribute.PosixFilePermissions;
import java.util.Set;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

/** 고정 root broker와 작은 요청·안전한 결과만 교환한다. 명령·비밀·archive 경로는 API에 없다. */
@Component
public class NexusBackupJobGateway {
    private final Path base;
    private final ObjectMapper mapper;
    private static final Set<String> STATUSES=Set.of("RUNNING","COMPLETED","FAILED","RECOVERY_REQUIRED","RECONCILING");
    private static final Set<String> STAGES=Set.of("PREFLIGHT","STOPPING","COPYING","VERIFYING","RESTORING_ORIGINAL","DONE");
    private static final Set<String> ERRORS=Set.of("PREFLIGHT_REJECTED","BACKUP_FAILED","ORIGINAL_RECOVERY_FAILED","RESULT_UNCERTAIN");
    public NexusBackupJobGateway(@Value("${deployops.nexus-backup.queue-root:/var/lib/deployops/nexus-backup}") String base) {
        // 고정 파일 프로토콜용 Jackson 2 mapper다. Spring MVC의 Jackson 3 설정·빈에 의존하지 않는다.
        this.mapper=new ObjectMapper(); this.base=Path.of(base).toAbsolutePath().normalize();
    }
    private NexusAgentBrokerTransport remote;
    @org.springframework.beans.factory.annotation.Autowired
    void configureAgentTransport(NexusAgentBrokerTransport remote) { this.remote = remote; }
    private boolean remoteEnabled() { return remote != null && remote.configured(); }
    private String relative(Path path) throws IOException {
        Path normalized = path.toAbsolutePath().normalize();
        if (!normalized.startsWith(base)) throw new IOException("BROKER_PATH_INVALID");
        return base.relativize(normalized).toString().replace('\\', '/');
    }
    boolean exists(Path path) throws IOException {
        if (remoteEnabled()) {
            String result=new String(remote.exchange("exists", relative(path), null), java.nio.charset.StandardCharsets.UTF_8);
            if(result.equals("true"))return true;
            if(result.equals("false"))return false;
            throw new IOException("BROKER_FILE_STATE_UNKNOWN");
        }
        rejectLinks(path);
        if (Files.exists(path, LinkOption.NOFOLLOW_LINKS)) return true;
        if (Files.notExists(path, LinkOption.NOFOLLOW_LINKS)) return false;
        throw new IOException("BROKER_FILE_STATE_UNKNOWN");
    }
    public boolean ready() {
        try {
            JsonNode ready=readRootFile(base.resolve("results/ready.json"));
            return ready.isObject() && ready.path("schema").isIntegralNumber() && ready.path("schema").canConvertToInt() && ready.path("schema").intValue()==1 && ready.path("ready").isBoolean()
                && ready.path("ready").booleanValue() && ready.path("externalWritersManaged").isBoolean()
                && ready.path("externalWritersManaged").booleanValue();
        } catch(Exception ex) { return false; }
    }
    public String readinessCode() {
        try {
            String code=readRootFile(base.resolve("results/ready.json")).path("readinessCode").asText("");
            return Set.of("OPERATOR_STORAGE_AND_WRITER_CONFIRMATION_REQUIRED","SOURCE_STORAGE_KEY_OR_HEALTH_UNAVAILABLE").contains(code)?code:"";
        } catch(Exception unavailable){return "RUNNER_STATE_UNAVAILABLE";}
    }
    public void submit(long id) throws IOException {
        if(id<=0 || !ready()) throw new IOException("BROKER_NOT_READY");
        publish(id+".json",java.util.Map.of("schema",1,"requestId",id,"action","BACKUP"));
    }
    public void submitRecoveryCheck(long id,long checkId) throws IOException {
        if(id<=0||checkId<=0) throw new IOException("CHECK_ID_INVALID");
        publish(id+"-"+checkId+".check.json",java.util.Map.of("schema",1,"requestId",id,"action","CHECK_RECOVERY","checkId",checkId));
    }
    void publish(String filename,java.util.Map<String,Object> payload) throws IOException {
        if (remoteEnabled()) { remote.exchange("publish", "inbox/" + filename, payload); return; }
        Path inbox=base.resolve("inbox");
        rejectLinks(inbox);
        if(!Files.isDirectory(inbox,LinkOption.NOFOLLOW_LINKS)) throw new IOException("INBOX_ABSENT");
        Path target=inbox.resolve(filename);
        Path tmp=Files.createTempFile(inbox,".job-",".tmp",PosixFilePermissions.asFileAttribute(PosixFilePermissions.fromString("rw-------")));
        try {
            Files.writeString(tmp,mapper.writeValueAsString(payload),StandardOpenOption.TRUNCATE_EXISTING);
            Files.move(tmp,target); // 같은 filesystem의 닫힌 파일 rename. 기존 요청은 덮어쓰지 않는다.
        } finally { Files.deleteIfExists(tmp); }
    }
    public SafeResult result(long id) throws IOException {
        Path p=base.resolve("results/"+id+".json");
        if(!exists(p)) return null;
        return parseResult(readRootFile(p),id);
    }
    public SafeResult recoveryCheckResult(long id,long checkId) throws IOException {
        Path p=base.resolve("results/"+id+"-"+checkId+".check.json");
        if(!exists(p)) return null;
        return parseCheckResult(readRootFile(p),id,checkId);
    }
    static SafeResult parseCheckResult(JsonNode n,long id,long checkId) throws IOException {
        if(n==null||!n.isObject()||!n.path("checkId").isIntegralNumber()||!n.path("checkId").canConvertToLong()||n.path("checkId").longValue()!=checkId) throw new IOException("CHECK_RESULT_ID");
        var backup=n.deepCopy();((com.fasterxml.jackson.databind.node.ObjectNode)backup).remove("checkId");
        SafeResult result=parseResult(backup,id);
        if(result.artifactId()!=null||result.manifestSha256()!=null||result.completedAt()==null) throw new IOException("CHECK_RESULT_CONTRACT");
        if(result.originalStateRestored()) {
            if(!result.status().equals("FAILED")||!result.stage().equals("DONE")||!"BACKUP_FAILED".equals(result.errorCode())) throw new IOException("CHECK_RESULT_RECOVERY_PROOF");
        } else if(!Set.of("RECOVERY_REQUIRED","RECONCILING").contains(result.status())||!Set.of("ORIGINAL_RECOVERY_FAILED","RESULT_UNCERTAIN").contains(result.errorCode())) throw new IOException("CHECK_RESULT_UNCONFIRMED");
        return result;
    }
    JsonNode readRootFile(Path p) throws IOException {
        return decode(readRootBytes(p),mapper);
    }
    byte[] readRootBytes(Path p) throws IOException {
        if (remoteEnabled()) return remote.exchange("read", relative(p), null);
        rejectLinks(p);
        if(!Files.isRegularFile(p,LinkOption.NOFOLLOW_LINKS)||Files.size(p)>8192) throw new IOException("RESULT_FILE_INVALID");
        if(!Integer.valueOf(0).equals(Files.getAttribute(p,"unix:uid",LinkOption.NOFOLLOW_LINKS))
            || !Files.getPosixFilePermissions(p,LinkOption.NOFOLLOW_LINKS).equals(PosixFilePermissions.fromString("rw-r-----"))) throw new IOException("RESULT_OWNER_MODE");
        try(var in=Files.newInputStream(p)) {
            byte[] bytes=in.readNBytes(8193);
            if(bytes.length>8192) throw new IOException("RESULT_TOO_LARGE");
            return bytes;
        }
    }
    void publishExactCheck(String filename,java.util.Map<String,Object> payload) throws IOException {
        if (remoteEnabled()) { remote.exchange("publish-exact", "inbox/" + filename, payload); return; }
        Path target=base.resolve("inbox").resolve(filename);
        rejectLinks(target);
        JsonNode expected=mapper.valueToTree(payload);
        if(Files.exists(target,LinkOption.NOFOLLOW_LINKS)){
            verifyPublishedCheck(target,expected);
            return;
        }
        Path inbox=target.getParent();
        if(!Files.isDirectory(inbox,LinkOption.NOFOLLOW_LINKS))throw new IOException("INBOX_ABSENT");
        Path tmp=Files.createTempFile(inbox,".check-",".tmp",PosixFilePermissions.asFileAttribute(PosixFilePermissions.fromString("rw-------")));
        try{
            Files.writeString(tmp,mapper.writeValueAsString(payload),StandardOpenOption.TRUNCATE_EXISTING);
            try(var channel=java.nio.channels.FileChannel.open(tmp,StandardOpenOption.WRITE)){channel.force(true);}
            // 같은 token의 경쟁 게시도 기존 이름을 덮어쓰지 않는다.
            try{Files.createLink(target,tmp);}
            catch(FileAlreadyExistsException existing){verifyPublishedCheck(target,expected);}
        }finally{Files.deleteIfExists(tmp);}
    }
    private void verifyPublishedCheck(Path target,JsonNode expected)throws IOException{
        rejectLinks(target);
        if(!Files.isRegularFile(target,LinkOption.NOFOLLOW_LINKS)||Files.size(target)>8192
                ||!Files.getPosixFilePermissions(target,LinkOption.NOFOLLOW_LINKS).equals(PosixFilePermissions.fromString("rw-------")))
            throw new IOException("CHECK_INBOX_MODE");
        // app이 쓴 inbox와 root가 쓴 결과의 소유 조건은 다르다.
        var appOwner=target.getFileSystem().getUserPrincipalLookupService().lookupPrincipalByName(System.getProperty("user.name"));
        if(!Files.getOwner(target,LinkOption.NOFOLLOW_LINKS).equals(appOwner))
            throw new IOException("CHECK_INBOX_OWNER");
        try(var in=Files.newInputStream(target,LinkOption.NOFOLLOW_LINKS)){
            byte[] raw=in.readNBytes(8193);
            verifyCheckPayload(raw,expected);
        }
    }
    static void verifyCheckPayload(byte[] raw,JsonNode expected)throws IOException{
        if(raw.length>8192||!decode(raw,new ObjectMapper()).equals(expected))throw new IOException("CHECK_INBOX_IDENTITY");
    }
    private void rejectLinks(Path p) throws IOException {
        for(Path part=p;part!=null;part=part.getParent()) if(Files.isSymbolicLink(part)) throw new IOException("QUEUE_LINK");
    }
    static SafeResult parseResult(JsonNode n,long id) throws IOException {
        if(n==null||!n.isObject()) throw new IOException("RESULT_OBJECT_REQUIRED");
        Set<String> fields=Set.of("schema","requestId","status","stage","artifactId","manifestSha256","errorCode","originalStateRestored","completedAt");
        var names=n.fieldNames(); while(names.hasNext()) if(!fields.contains(names.next())) throw new IOException("UNSAFE_RESULT_FIELD");
        String status=n.path("status").asText(),stage=n.path("stage").asText();
        if(!n.path("schema").isIntegralNumber()||!n.path("schema").canConvertToInt()||n.path("schema").intValue()!=1||!n.path("requestId").isIntegralNumber()||!n.path("requestId").canConvertToLong()||n.path("requestId").longValue()!=id
            ||!STATUSES.contains(status)||!STAGES.contains(stage)||!n.path("originalStateRestored").isBoolean()) throw new IOException("RESULT_CONTRACT");
        String error=text(n,"errorCode"),artifact=text(n,"artifactId"),hash=text(n,"manifestSha256");
        if(error!=null&&!ERRORS.contains(error)) throw new IOException("RESULT_ERROR_CODE");
        if(artifact!=null&&!artifact.equals("nexus-backup-"+id)) throw new IOException("RESULT_ARTIFACT_ID");
        if(hash!=null&&!hash.matches("[0-9a-f]{64}")) throw new IOException("RESULT_HASH");
        boolean restored=n.path("originalStateRestored").booleanValue();
        java.time.LocalDateTime completed=null;
        if(n.hasNonNull("completedAt")) {
            try { completed=java.time.OffsetDateTime.parse(n.get("completedAt").asText()).withOffsetSameInstant(java.time.ZoneOffset.UTC).toLocalDateTime(); }
            catch(Exception ex) { throw new IOException("RESULT_TIME"); }
        }
        if(status.equals("COMPLETED")&&(!restored||artifact==null||hash==null||completed==null||error!=null||!stage.equals("DONE"))) throw new IOException("INCOMPLETE_BACKUP_RESULT");
        if(status.equals("FAILED")&&(error==null||completed==null)) throw new IOException("FAILED_RESULT_CONTRACT");
        if(status.equals("FAILED")&&!restored) { status="RECOVERY_REQUIRED"; error="ORIGINAL_RECOVERY_FAILED"; }
        return new SafeResult(status,stage,artifact,hash,error,restored,completed);
    }
    private static String text(JsonNode n,String key) throws IOException {
        if(!n.hasNonNull(key)) return null;
        if(!n.get(key).isTextual()) throw new IOException("RESULT_TYPE");
        return n.get(key).textValue();
    }
    static JsonNode decode(byte[] bytes,ObjectMapper mapper) throws IOException {
        try(var parser=mapper.getFactory().createParser(bytes)) {
            parser.enable(com.fasterxml.jackson.core.JsonParser.Feature.STRICT_DUPLICATE_DETECTION);
            JsonNode value=mapper.readTree(parser);
            if(parser.nextToken()!=null) throw new IOException("MULTIPLE_RESULT_VALUES");
            return value;
        }
    }
    public record SafeResult(String status,String stage,String artifactId,String manifestSha256,String errorCode,boolean originalStateRestored,java.time.LocalDateTime completedAt) {}
}
