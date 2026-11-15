package com.portfolio.deployops.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.HexFormat;
import org.junit.jupiter.api.Test;
import static org.assertj.core.api.Assertions.*;

class NexusExistingCloneGatewayTest {
    final ObjectMapper mapper=new ObjectMapper();
    ObjectNode primary(){return new NexusRestoreGatewayContractTest().ready();}
    ObjectNode check(ObjectNode primary)throws Exception{
        var n=mapper.createObjectNode();n.put("schema",2);n.put("requestId",1);n.put("checkId",7);n.put("backupRequestId",2);
        n.put("manifestSha256","a".repeat(64));n.put("status","VERIFIED_HEALTH_READY");n.put("reasonCode","EXISTING_CLONE_ACCESS_CONFIRMED");
        n.put("originalProjectionPreserved",true);n.put("mutationUncertain",false);n.put("completedAt","2026-10-12T01:00:00Z");
        n.put("evidenceSha256","e".repeat(64));n.put("primarySha256",sha(bytes(primary)));n.set("primaryResult",primary.deepCopy());return n;
    }
    byte[] bytes(ObjectNode n)throws Exception{return mapper.writeValueAsBytes(n);}
    String sha(byte[] raw)throws Exception{return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(raw));}
    @Test void exactPrimaryRawAndParsedProofAreBothRequired()throws Exception{
        var p=primary();var n=check(p);var result=NexusRestoreJobGateway.parseReadinessCheck(n,bytes(p),1,7);
        assertThat(result.primary().status()).isEqualTo("HEALTH_READY");assertThat(result.check().status()).isEqualTo("VERIFIED_HEALTH_READY");
        assertThatThrownBy(()->NexusRestoreJobGateway.parseReadinessCheck(n,(p.toString()+" ").getBytes(StandardCharsets.UTF_8),1,7)).isInstanceOf(java.io.IOException.class);
        n.withObject("primaryResult").put("cloneContainerId","d".repeat(64));
        assertThatThrownBy(()->NexusRestoreJobGateway.parseReadinessCheck(n,bytes(p),1,7)).isInstanceOf(java.io.IOException.class);
    }
    @Test void missingExtraStaleAndCrossActionResultsReject()throws Exception{
        var p=primary();var n=check(p);
        for(String key:java.util.List.of("schema","requestId","checkId","evidenceSha256","primarySha256","primaryResult")){
            var bad=n.deepCopy();bad.remove(key);assertThatThrownBy(()->NexusRestoreJobGateway.parseReadinessCheck(bad,bytes(p),1,7)).isInstanceOf(java.io.IOException.class);
        }
        var extra=n.deepCopy();extra.put("url","http://unsafe");assertThatThrownBy(()->NexusRestoreJobGateway.parseReadinessCheck(extra,bytes(p),1,7)).isInstanceOf(java.io.IOException.class);
        assertThatThrownBy(()->NexusRestoreJobGateway.parseReadinessCheck(n,bytes(p),1,8)).isInstanceOf(java.io.IOException.class);
        assertThatThrownBy(()->NexusRestoreJobGateway.parseCheck(n,1,7)).isInstanceOf(java.io.IOException.class);
    }
    @Test void nonHealthOrWrongBackupCannotSupplyReadiness()throws Exception{
        for(String field:java.util.List.of("status","backupRequestId","manifestSha256","mutationUncertain")){
            var p=primary();switch(field){case "status"->p.put(field,"RUNNING");case "backupRequestId"->p.put(field,9);case "manifestSha256"->p.put(field,"f".repeat(64));default->p.put(field,true);}
            var n=check(p);assertThatThrownBy(()->NexusRestoreJobGateway.parseReadinessCheck(n,bytes(p),1,7)).isInstanceOf(java.io.IOException.class);
        }
    }
    @Test void unconfirmedNeverEmbedsPrimaryOrDigest()throws Exception{
        var n=check(primary());n.put("status","UNCONFIRMED");n.put("reasonCode","STATE_UNCONFIRMED");n.put("originalProjectionPreserved",false);n.put("mutationUncertain",true);
        for(String key:java.util.List.of("primaryResult","primarySha256","evidenceSha256"))n.putNull(key);
        assertThat(NexusRestoreJobGateway.parseReadinessCheck(n,null,1,7).primary()).isNull();
        n.put("evidenceSha256","a".repeat(64));assertThatThrownBy(()->NexusRestoreJobGateway.parseReadinessCheck(n,null,1,7)).isInstanceOf(java.io.IOException.class);
    }
    @Test void largestAllowedExpectationsFitExistingRootReadBound()throws Exception{
        var p=primary();var expected=p.putArray("expectedArtifacts");
        for(int i=0;i<16;i++){var item=expected.addObject();item.put("ref","artifact_"+i+"x".repeat(51));item.put("sha256","a".repeat(64));item.put("baselineReceiptSha256","b".repeat(64));}
        var access=p.putObject("cloneAccess");access.put("schema",2);access.put("transport","EXECUTION_HOST_BRIDGE_ACCESS");access.put("executionHost","h".repeat(101));
        access.put("cloneContainerId","b".repeat(64));access.put("networkId","d".repeat(64));access.put("containerIpv4","192.168.255.255");access.put("containerPort",8081);access.put("requiresVerifiedRelay",true);
        var n=check(p);assertThat(bytes(n).length).isLessThanOrEqualTo(8192);
        assertThat(NexusRestoreJobGateway.parseReadinessCheck(n,bytes(p),1,7).primary().expectedArtifacts()).contains("artifact_15");
    }
    @Test void duplicateRootKeysRejectEvenIfDigestMatches()throws Exception{
        var p=primary();String raw=p.toString().replace("\"requestId\":1","\"requestId\":1,\"requestId\":1");var n=check(p);n.put("primarySha256",sha(raw.getBytes(StandardCharsets.UTF_8)));
        assertThatThrownBy(()->NexusRestoreJobGateway.parseReadinessCheck(n,raw.getBytes(StandardCharsets.UTF_8),1,7)).isInstanceOf(java.io.IOException.class);
    }
    @Test void sameInboxPayloadCanResumeButAnotherTokenOrActionCannot()throws Exception{
        var expected=mapper.valueToTree(java.util.Map.of("schema",1,"requestId",1,"checkId",7,"action",NexusRestoreJobGateway.EXISTING_CLONE_CHECK,"backupRequestId",2,"manifestSha256","a".repeat(64)));
        NexusBackupJobGateway.verifyCheckPayload(mapper.writeValueAsBytes(expected),expected);
        var different=expected.deepCopy();((ObjectNode)different).put("action","CHECK_RESTORE_FAILURE");
        assertThatThrownBy(()->NexusBackupJobGateway.verifyCheckPayload(mapper.writeValueAsBytes(different),expected)).isInstanceOf(java.io.IOException.class);
        var stale=expected.deepCopy();((ObjectNode)stale).put("checkId",8);
        assertThatThrownBy(()->NexusBackupJobGateway.verifyCheckPayload(mapper.writeValueAsBytes(stale),expected)).isInstanceOf(java.io.IOException.class);
        assertThatThrownBy(()->NexusBackupJobGateway.verifyCheckPayload("{} {}".getBytes(StandardCharsets.UTF_8),expected)).isInstanceOf(java.io.IOException.class);
    }
}
