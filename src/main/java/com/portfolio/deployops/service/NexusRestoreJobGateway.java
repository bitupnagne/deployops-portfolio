package com.portfolio.deployops.service;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.IOException;
import java.nio.file.*;
import java.time.*;
import java.util.*;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
@Component
public class NexusRestoreJobGateway {
    private final NexusBackupJobGateway files;
    private final Path base;
    private final ObjectMapper mapper=new ObjectMapper();
    public NexusRestoreJobGateway(NexusBackupJobGateway files,@Value("${deployops.nexus-backup.queue-root:/var/lib/deployops/nexus-backup}")String base){
        this.files=files;
        this.base=Path.of(base).toAbsolutePath().normalize();
    }
    public boolean ready(){
        try {
            JsonNode n=files.readRootFile(base.resolve("results/ready.json"));
            return n.path("schema").isIntegralNumber()
                    &&n.path("schema").canConvertToInt()
                    &&n.path("schema").intValue()==1
                    &&n.path("restoreReady").isBoolean()
                    &&n.path("restoreReady").booleanValue();
        }
        catch (Exception e){
            return false;
        }
    }
    public void submit(long id,long backupId,String manifest)throws IOException{
        if (id <= 0 || backupId <= 0 || manifest == null || !manifest.matches("[0-9a-f]{64}")) {
            throw new IOException("RESTORE_JOB_IDENTITY");
        }
        // Only this exception proves publish was never entered. Existing spool
        // evidence or any publish I/O failure remains ambiguous and must hold.
        if (!ready()) {
            if (files.exists(base.resolve("inbox/restore-" + id + ".json"))
                    || files.exists(base.resolve("results/restore-" + id + ".json"))) {
                throw new IOException("RESTORE_EXISTING_PUBLICATION");
            }
            throw new PublicationNotStarted();
        }
        files.publish("restore-"+id+".json",Map.of("schema",1,"requestId",id,"action","RESTORE","backupRequestId",backupId,"manifestSha256",manifest));
    }
    public static final class PublicationNotStarted extends IOException {
        public PublicationNotStarted() {
            super("RESTORE_NOT_READY_BEFORE_PUBLICATION");
        }
    }

    public SafeResult result(long id)throws IOException{
        Path path=base.resolve("results/restore-"+id+".json");
        if (!files.exists(path))return null;
        return parse(files.readRootFile(path),id);
    }
    public void submitRecoveryCheck(long id,long checkId,long backupId,String manifest)throws IOException{
        if(id<=0||checkId<=0||backupId<=0||manifest==null||!manifest.matches("[0-9a-f]{64}"))throw new IOException("RESTORE_CHECK_IDENTITY");
        files.publish("restore-"+id+"-"+checkId+".check.json",Map.of("schema",1,"requestId",id,
                "action","CHECK_RESTORE_FAILURE","checkId",checkId,"backupRequestId",backupId,"manifestSha256",manifest));
    }
    public static final String EXISTING_CLONE_CHECK="CHECK_EXISTING_CLONE_READINESS";
    public void submitExistingCloneCheck(long id,long checkId,long backupId,String manifest)throws IOException{
        if(id<=0||checkId<=0||backupId<=0||manifest==null||!manifest.matches("[0-9a-f]{64}"))throw new IOException("RESTORE_CHECK_IDENTITY");
        files.publishExactCheck("restore-"+id+"-"+checkId+".check.json",Map.of("schema",1,"requestId",id,
                "action",EXISTING_CLONE_CHECK,"checkId",checkId,"backupRequestId",backupId,"manifestSha256",manifest));
    }
    public ReadinessCheckResult existingCloneCheckResult(long id,long checkId)throws IOException{
        Path path=base.resolve("results/restore-"+id+"-"+checkId+".check.json");
        if(!files.exists(path))return null;
        JsonNode check=files.readRootFile(path);
        byte[] primary=null;
        if("VERIFIED_HEALTH_READY".equals(check.path("status").asText()))
            primary=files.readRootBytes(base.resolve("results/restore-"+id+".json"));
        return parseReadinessCheck(check,primary,id,checkId);
    }
    static ReadinessCheckResult parseReadinessCheck(JsonNode n,byte[] primaryBytes,long id,long checkId)throws IOException{
        Set<String> fields=Set.of("schema","requestId","checkId","backupRequestId","manifestSha256","status","reasonCode",
                "originalProjectionPreserved","mutationUncertain","completedAt","evidenceSha256","primarySha256","primaryResult");
        if(n==null||!n.isObject()||n.size()!=fields.size())throw new IOException("RESTORE_READINESS_CHECK_FIELDS");
        var names=n.fieldNames();while(names.hasNext())if(!fields.contains(names.next()))throw new IOException("RESTORE_READINESS_CHECK_FIELDS");
        if(!n.path("schema").isIntegralNumber()||!n.path("schema").canConvertToInt()||n.path("schema").intValue()!=2)
            throw new IOException("RESTORE_READINESS_CHECK_SCHEMA");
        var base=(com.fasterxml.jackson.databind.node.ObjectNode)n.deepCopy();
        base.remove(List.of("evidenceSha256","primarySha256","primaryResult"));base.put("schema",1);
        boolean verified="VERIFIED_HEALTH_READY".equals(text(n,"status"));
        if(verified){
            if(!"EXISTING_CLONE_ACCESS_CONFIRMED".equals(text(n,"reasonCode"))
                    ||!n.path("originalProjectionPreserved").isBoolean()||!n.path("originalProjectionPreserved").booleanValue()
                    ||!n.path("mutationUncertain").isBoolean()||n.path("mutationUncertain").booleanValue())
                throw new IOException("RESTORE_READINESS_CHECK_PROOF");
            base.put("status","UNCONFIRMED");base.put("reasonCode","STATE_UNCONFIRMED");
            base.put("originalProjectionPreserved",false);base.put("mutationUncertain",true);
        }
        CheckResult identity=parseCheck(base,id,checkId);
        if(!verified){
            if(!n.path("evidenceSha256").isNull()||!n.path("primarySha256").isNull()||!n.path("primaryResult").isNull()
                    ||identity.originalProjectionPreserved())throw new IOException("RESTORE_READINESS_UNCONFIRMED");
            return new ReadinessCheckResult(identity,null,null,null);
        }
        String evidence=text(n,"evidenceSha256"),primarySha=text(n,"primarySha256");
        if(evidence==null||!evidence.matches("[0-9a-f]{64}")||primarySha==null||!primarySha.matches("[0-9a-f]{64}")
                ||primaryBytes==null||primaryBytes.length>8192)throw new IOException("RESTORE_READINESS_BINDING");
        String actual;
        try{actual=java.util.HexFormat.of().formatHex(java.security.MessageDigest.getInstance("SHA-256").digest(primaryBytes));}
        catch(java.security.NoSuchAlgorithmException e){throw new IOException("RESTORE_PRIMARY_DIGEST",e);}
        JsonNode primary=NexusBackupJobGateway.decode(primaryBytes,new ObjectMapper());
        if(!actual.equals(primarySha)||!primary.equals(n.get("primaryResult")))throw new IOException("RESTORE_PRIMARY_BINDING");
        SafeResult parsed=parse(primary,id);
        if(!"HEALTH_READY".equals(parsed.status())||parsed.backupRequestId()!=identity.backupRequestId()
                ||!parsed.manifestSha256().equals(identity.manifestSha256()))throw new IOException("RESTORE_PRIMARY_IDENTITY");
        return new ReadinessCheckResult(new CheckResult(identity.backupRequestId(),identity.manifestSha256(),
                "VERIFIED_HEALTH_READY","EXISTING_CLONE_ACCESS_CONFIRMED",true,false,identity.completedAt()),evidence,primarySha,parsed);
    }
    public record ReadinessCheckResult(CheckResult check,String evidenceSha256,String primarySha256,SafeResult primary){}
    public CheckResult recoveryCheckResult(long id,long checkId)throws IOException{
        Path path=base.resolve("results/restore-"+id+"-"+checkId+".check.json");
        if(!files.exists(path))return null;
        return parseCheck(files.readRootFile(path),id,checkId);
    }
    static CheckResult parseCheck(JsonNode n,long id,long checkId)throws IOException{
        Set<String> fields=Set.of("schema","requestId","checkId","backupRequestId","manifestSha256","status","reasonCode","originalProjectionPreserved","mutationUncertain","completedAt");
        if(n==null||!n.isObject()||n.size()!=fields.size())throw new IOException("RESTORE_CHECK_FIELDS");
        var keys=n.fieldNames();while(keys.hasNext())if(!fields.contains(keys.next()))throw new IOException("RESTORE_CHECK_FIELDS");
        for(String key:List.of("schema","requestId","checkId","backupRequestId"))
            if(!n.path(key).isIntegralNumber()||!n.path(key).canConvertToLong())throw new IOException("RESTORE_CHECK_IDENTITY");
        if(n.get("schema").longValue()!=1||n.get("requestId").longValue()!=id||n.get("checkId").longValue()!=checkId||n.get("backupRequestId").longValue()<=0)throw new IOException("RESTORE_CHECK_IDENTITY");
        String manifest=text(n,"manifestSha256"),status=text(n,"status"),reason=text(n,"reasonCode");
        if(manifest==null||!manifest.matches("[0-9a-f]{64}")||!n.path("originalProjectionPreserved").isBoolean()||!n.path("mutationUncertain").isBoolean())throw new IOException("RESTORE_CHECK_PROOF");
        boolean preserved=n.get("originalProjectionPreserved").booleanValue(),uncertain=n.get("mutationUncertain").booleanValue();
        if(!("VERIFIED_FAILED".equals(status)&&"PRECONTAINER_FAILURE_CONFIRMED".equals(reason)&&preserved&&!uncertain)
                &&!("UNCONFIRMED".equals(status)&&"STATE_UNCONFIRMED".equals(reason)&&uncertain))throw new IOException("RESTORE_CHECK_PROOF");
        LocalDateTime completed;
        try{OffsetDateTime time=OffsetDateTime.parse(text(n,"completedAt"));if(!time.getOffset().equals(ZoneOffset.UTC))throw new IllegalArgumentException();completed=time.toLocalDateTime();}
        catch(Exception e){throw new IOException("RESTORE_CHECK_TIME");}
        return new CheckResult(n.get("backupRequestId").longValue(),manifest,status,reason,preserved,uncertain,completed);
    }
    public record CheckResult(long backupRequestId,String manifestSha256,String status,String reasonCode,boolean originalProjectionPreserved,boolean mutationUncertain,LocalDateTime completedAt){}
    static SafeResult parse(JsonNode n,long id)throws IOException{
        Set<String> allowed=Set.of("schema","requestId","backupRequestId","manifestSha256","status","stage","errorCode","cloneContainerId","cloneImageId","cloneName","cloneVolume","cloneNetwork","originalProjectionPreserved","mutationUncertain","expectedArtifacts","cloneAccess","completedAt");
        if (n==null||!n.isObject()||n.size()!=allowed.size()) throw new IOException("RESTORE_RESULT_OBJECT");
        var it=n.fieldNames();
        while(it.hasNext())if (!allowed.contains(it.next())) throw new IOException("RESTORE_UNSAFE_FIELD");
        if (!n.path("schema").isIntegralNumber()
                ||(!n.path("schema").canConvertToLong()
                ||n.path("schema").longValue()!=1)
                ||!n.path("requestId").isIntegralNumber()
                ||!n.path("requestId").canConvertToLong()
                ||n.path("requestId").longValue()!=id
                ||!n.path("backupRequestId").isIntegralNumber()
                ||!n.path("backupRequestId").canConvertToLong()
                ||n.path("backupRequestId").longValue()<=0) throw new IOException("RESTORE_RESULT_ID");
        String manifest=text(n,"manifestSha256"),status=text(n,"status"),stage=text(n,"stage"),error=text(n,"errorCode");
        if (manifest==null
                ||!manifest.matches("[0-9a-f]{64}")
                ||status==null
                ||stage==null
                ||!Set.of("RUNNING","HEALTH_READY","FAILED","RECOVERY_REQUIRED").contains(status)
                ||!Set.of("PREFLIGHT","VERIFYING","RESTORING","HEALTHCHECK","DONE").contains(stage)
                ||!n.path("originalProjectionPreserved").isBoolean()
                ||!n.path("mutationUncertain").isBoolean()) throw new IOException("RESTORE_RESULT_CONTRACT");
        if (error!=null
                &&!Set.of("PREFLIGHT_REJECTED","RESTORE_FAILED","RESULT_UNCERTAIN","ORIGINAL_CHANGED").contains(error)) throw new IOException("RESTORE_ERROR_CODE");
        boolean preserved=n.get("originalProjectionPreserved").booleanValue(),uncertain=n.get("mutationUncertain").booleanValue();
        String cid=text(n,"cloneContainerId"),image=text(n,"cloneImageId"),name=text(n,"cloneName"),volume=text(n,"cloneVolume"),network=text(n,"cloneNetwork");
        if (cid!=null&&!cid.matches("[0-9a-f]{64}")) throw new IOException("RESTORE_CID");
        if (image!=null&&!image.matches("sha256:[0-9a-f]{64}")) throw new IOException("RESTORE_IMAGE");
        if (name!=null
                &&!name.equals("nexus-restore-"+id)
                ||volume!=null
                &&!volume.equals("nexus-restore-data-"+id)
                ||network!=null
                &&!network.equals("nexus-restore-net-"+id)) throw new IOException("RESTORE_TARGET_IDENTITY");
        JsonNode checks=validateExpectations(n.path("expectedArtifacts"));
        JsonNode access=n.get("cloneAccess");
        if (access!=null&&!access.isNull()) {
            validateAccess(access);
            if (access.path("schema").intValue()==2
                    && !java.util.Objects.equals(cid, access.path("cloneContainerId").textValue())) {
                throw new IOException("RESTORE_ACCESS_CID");
            }
        }
        LocalDateTime completed=null;
        if (n.hasNonNull("completedAt")){
            try {
                completed=OffsetDateTime.parse(text(n,"completedAt")).withOffsetSameInstant(ZoneOffset.UTC).toLocalDateTime();
            }
            catch (Exception e){
                throw new IOException("RESTORE_RESULT_TIME");
            }
        }
        if (status.equals("HEALTH_READY")
                &&(uncertain
                ||!preserved
                ||error!=null
                ||completed==null
                ||access==null
                ||access.isNull()
                ||cid==null
                ||image==null
                ||name==null
                ||volume==null
                ||network==null
                ||!stage.equals("DONE"))) throw new IOException("RESTORE_HEALTH_PROOF");
        if (status.equals("FAILED")&&(uncertain||!preserved||error==null||completed==null)) throw new IOException("RESTORE_FAILURE_PROOF");
        if (status.equals("RECOVERY_REQUIRED")&&(!uncertain||error==null)) throw new IOException("RESTORE_HOLD_PROOF");
        return new SafeResult(n.get("backupRequestId").longValue(),manifest,status,stage,error,cid,image,name,volume,network,preserved,uncertain,checks.toString(),access==null
                ||access.isNull()?null:access.toString(),completed);
    }
    static JsonNode validateExpectations(JsonNode checks)throws IOException{
        if (checks==null||!checks.isArray()||checks.size()>16) throw new IOException("RESTORE_EXPECTATIONS");
        Set<String> refs=new HashSet<>();
        for(JsonNode c:checks){
            if (!c.isObject()
                    ||c.size()!=3
                    ||!c.path("ref").isTextual()
                    ||!c.path("ref").textValue().matches("[A-Za-z0-9_-]{1,64}")
                    ||!refs.add(c.get("ref").textValue())
                    ||!c.path("sha256").isTextual()
                    ||!c.get("sha256").textValue().matches("[0-9a-f]{64}")
                    ||!c.path("baselineReceiptSha256").isTextual()
                    ||!c.get("baselineReceiptSha256").textValue().matches("[0-9a-f]{64}")) throw new IOException("RESTORE_EXPECTATION_BINDING");
        }
        return checks;
    }
    static void validateAccess(JsonNode value) throws IOException {
        if (value.isObject() && value.path("schema").isIntegralNumber()
                && value.path("schema").canConvertToInt() && value.path("schema").intValue()==2) {
            Set<String> fields=Set.of("schema","transport","executionHost","cloneContainerId",
                    "networkId","containerIpv4","containerPort","requiresVerifiedRelay");
            Set<String> actual=new HashSet<>();
            value.fieldNames().forEachRemaining(actual::add);
            if (!actual.equals(fields)
                    ||!value.path("transport").isTextual()
                    ||!value.path("transport").textValue().equals("EXECUTION_HOST_BRIDGE_ACCESS")
                    ||!value.path("executionHost").isTextual()
                    ||!value.path("executionHost").textValue().matches("[A-Za-z0-9][A-Za-z0-9_.-]{0,100}")
                    ||!value.path("cloneContainerId").isTextual()
                    ||!value.path("cloneContainerId").textValue().matches("[0-9a-f]{64}")
                    ||!value.path("networkId").isTextual()
                    ||!value.path("networkId").textValue().matches("[0-9a-f]{64}")
                    ||!value.path("containerPort").isIntegralNumber()
                    ||!value.path("containerPort").canConvertToInt()
                    ||value.path("containerPort").intValue()!=8081
                    ||!value.path("requiresVerifiedRelay").isBoolean()
                    ||!value.path("requiresVerifiedRelay").booleanValue()) {
                throw new IOException("RESTORE_BRIDGE_ACCESS_SCOPE");
            }
            if (!value.path("containerIpv4").isTextual()
                    ||!privateBridgeIpv4(value.path("containerIpv4").textValue())) {
                throw new IOException("RESTORE_BRIDGE_ACCESS_IPV4");
            }
            return;
        }
        if (!value.isObject()
                ||value.size()!=4
                ||!value.path("schema").isIntegralNumber()
                ||!value.path("schema").canConvertToInt()
                ||value.path("schema").intValue()!=1
                ||!value.path("executionHost").isTextual()
                ||!value.path("executionHost").textValue().matches("[A-Za-z0-9][A-Za-z0-9_.-]{0,100}")
                ||!value.path("requiresVerifiedRelay").isBoolean()
                ||!value.path("requiresVerifiedRelay").booleanValue()) throw new IOException("RESTORE_ACCESS_SCOPE");
        JsonNode ports=value.path("ports");
        if (!ports.isArray()||ports.isEmpty()||ports.size()>3) throw new IOException("RESTORE_ACCESS_PORTS");
        Set<Integer> inner=new HashSet<>(),outer=new HashSet<>();
        for(JsonNode port:ports){
            if (!port.isObject()
                    ||port.size()!=3
                    ||!port.path("hostIp").isTextual()
                    ||!port.path("hostIp").textValue().equals("127.0.0.1")) throw new IOException("RESTORE_ACCESS_BIND");
            for(String key:List.of("containerPort","hostPort"))if (!port.path(key).isIntegralNumber()
                    ||!port.path(key).canConvertToInt()
                    ||port.path(key).intValue()<1
                    ||port.path(key).intValue()>65535) throw new IOException("RESTORE_ACCESS_PORT_TYPE");
            if (!inner.add(port.get("containerPort").intValue())
                    ||!outer.add(port.get("hostPort").intValue())) throw new IOException("RESTORE_ACCESS_DUPLICATE");
        }
        if (!inner.contains(8081)) throw new IOException("RESTORE_GUI_PORT_MISSING");
    }
    private static boolean privateBridgeIpv4(String address) {
        if (!address.matches("(?:0|[1-9][0-9]{0,2})(?:\\.(?:0|[1-9][0-9]{0,2})){3}")) return false;
        String[] parts=address.split("\\.");
        int[] octets=new int[4];
        for(int i=0;i<4;i++) {
            octets[i]=Integer.parseInt(parts[i]);
            if(octets[i]>255) return false;
        }
        return octets[0]==10 || octets[0]==172 && octets[1]>=16 && octets[1]<=31
                || octets[0]==192 && octets[1]==168;
    }
    private static String text(JsonNode n,String k)throws IOException{
        if (!n.hasNonNull(k))return null;
        if (!n.get(k).isTextual()) throw new IOException("RESTORE_FIELD_TYPE");
        return n.get(k).textValue();
    }
    public record SafeResult(long backupRequestId,String manifestSha256,String status,String stage,String errorCode,String cloneContainerId,String cloneImageId,String cloneName,String cloneVolume,String cloneNetwork,boolean originalProjectionPreserved,boolean mutationUncertain,String expectedArtifacts,String cloneAccess,LocalDateTime completedAt){
    }
}
