package com.portfolio.deployops.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.junit.jupiter.api.Test;
import static org.assertj.core.api.Assertions.*;

class NexusPromotionJobGatewayTest {
    final ObjectMapper mapper=new ObjectMapper();
    ObjectNode completed(){
        ObjectNode node=mapper.createObjectNode();node.put("schema",1);node.put("requestId",5);node.put("restoreRequestId",2);node.put("status","COMPLETED");node.put("canonicalContainerId","a".repeat(64));node.put("rollbackContainerId","b".repeat(64));node.putNull("errorCode");node.put("completedAt","2026-10-18T00:00:00Z");return node;
    }
    @Test void completeRequiresDistinctBoundCanonicalAndRollbackIds()throws Exception{
        assertThat(NexusPromotionJobGateway.parse(completed(),5,2).status()).isEqualTo("COMPLETED");
        var same=completed();same.put("rollbackContainerId","a".repeat(64));assertThatThrownBy(()->NexusPromotionJobGateway.parse(same,5,2)).hasMessageContaining("PROOF");
    }
    @Test void resultFromAnotherRestoreCannotReleaseReservation(){
        assertThatThrownBy(()->NexusPromotionJobGateway.parse(completed(),5,3)).hasMessageContaining("ID");
    }
    @Test void arbitraryPathsOrCommandsNeverEnterSafeResult(){
        var extra=completed();extra.put("keyFile","/root/private");assertThatThrownBy(()->NexusPromotionJobGateway.parse(extra,5,2)).hasMessageContaining("FIELDS");
    }
    @Test void malformedIdentityAndNonUtcCompletionAreRejected(){
        var bad=completed();bad.put("requestId","5");assertThatThrownBy(()->NexusPromotionJobGateway.parse(bad,5,2)).hasMessageContaining("ID");
        var time=completed();time.put("completedAt","2026-10-18T09:00:00+09:00");assertThatThrownBy(()->NexusPromotionJobGateway.parse(time,5,2)).hasMessageContaining("TIME");
    }
}
