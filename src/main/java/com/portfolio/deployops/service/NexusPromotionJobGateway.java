package com.portfolio.deployops.service;

import com.fasterxml.jackson.databind.JsonNode;
import java.io.IOException;
import java.nio.file.*;
import java.time.*;
import java.util.Map;
import java.util.Set;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

/** root 큐에는 검증한 복원 요청 ID만 전달한다. 컨테이너·경로·명령은 받지 않는다. */
@Component
public class NexusPromotionJobGateway {
    private final NexusBackupJobGateway files;
    private final Path base;
    public NexusPromotionJobGateway(NexusBackupJobGateway files,@Value("${deployops.nexus-backup.queue-root:/var/lib/deployops/nexus-backup}") String base) {
        this.files=files;this.base=Path.of(base).toAbsolutePath().normalize();
    }
    public boolean ready() {
        try {JsonNode value=files.readRootFile(base.resolve("results/ready.json"));return value.path("schema").isIntegralNumber()&&value.path("schema").asLong()==1&&value.path("ready").isBoolean()&&value.path("ready").booleanValue()&&value.path("promotionReady").isBoolean()&&value.path("promotionReady").booleanValue();}
        catch(Exception unavailable){return false;}
    }
    public void submit(long id,long restoreId)throws IOException {
        if(id<=0||restoreId<=0||!ready())throw new IOException("PROMOTION_NOT_READY");
        files.publish("promotion-"+id+".json",Map.of("schema",1,"requestId",id,"restoreRequestId",restoreId,"action","PROMOTE"));
    }
    public Result result(long id,long restoreId)throws IOException {
        Path path=base.resolve("results/promotion-"+id+".json");
        if(!files.exists(path))return null;
        return parse(files.readRootFile(path),id,restoreId);
    }
    static Result parse(JsonNode value,long id,long restoreId)throws IOException {
        Set<String> fields=Set.of("schema","requestId","restoreRequestId","status","canonicalContainerId","rollbackContainerId","errorCode","completedAt");
        if(value==null||!value.isObject()||value.size()!=fields.size())throw new IOException("PROMOTION_RESULT_FIELDS");
        var keys=value.fieldNames();while(keys.hasNext())if(!fields.contains(keys.next()))throw new IOException("PROMOTION_RESULT_FIELDS");
        for(String key:java.util.List.of("schema","requestId","restoreRequestId"))if(!value.path(key).isIntegralNumber()||!value.path(key).canConvertToLong())throw new IOException("PROMOTION_RESULT_ID");
        if(value.get("schema").asLong()!=1||value.get("requestId").asLong()!=id||value.get("restoreRequestId").asLong()!=restoreId)throw new IOException("PROMOTION_RESULT_ID");
        String status=text(value,"status"),canonical=text(value,"canonicalContainerId"),rollback=text(value,"rollbackContainerId"),error=text(value,"errorCode");
        if(!Set.of("RUNNING","COMPLETED","FAILED","RECOVERY_REQUIRED").contains(status))throw new IOException("PROMOTION_RESULT_STATUS");
        for(String cid:java.util.Arrays.asList(canonical,rollback))if(cid!=null&&!cid.matches("[0-9a-f]{64}"))throw new IOException("PROMOTION_RESULT_CONTAINER");
        if(error!=null&&!Set.of("PREFLIGHT_REJECTED","PROMOTION_FAILED","RESULT_UNCERTAIN").contains(error))throw new IOException("PROMOTION_RESULT_ERROR");
        LocalDateTime completed=null;
        if("RUNNING".equals(status)&&!value.path("completedAt").isNull())throw new IOException("PROMOTION_RESULT_TIME");
        if(!"RUNNING".equals(status))try {var time=OffsetDateTime.parse(text(value,"completedAt"));if(!time.getOffset().equals(ZoneOffset.UTC))throw new IllegalArgumentException();completed=time.toLocalDateTime();}
        catch(Exception badTime){throw new IOException("PROMOTION_RESULT_TIME");}
        if("COMPLETED".equals(status)&&(canonical==null||rollback==null||canonical.equals(rollback)||error!=null))throw new IOException("PROMOTION_RESULT_PROOF");
        if("FAILED".equals(status)&&!"PREFLIGHT_REJECTED".equals(error))throw new IOException("PROMOTION_RESULT_PROOF");
        return new Result(status,canonical,rollback,error,completed);
    }
    private static String text(JsonNode node,String key)throws IOException {if(node.path(key).isNull())return null;if(!node.path(key).isTextual())throw new IOException("PROMOTION_RESULT_TEXT");return node.get(key).asText();}
    public record Result(String status,String canonicalContainerId,String rollbackContainerId,String errorCode,LocalDateTime completedAt){}
}
