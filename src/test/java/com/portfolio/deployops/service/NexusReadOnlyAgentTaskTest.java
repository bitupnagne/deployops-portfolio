package com.portfolio.deployops.service;
import org.junit.jupiter.api.Test;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;
class NexusReadOnlyAgentTaskTest {
    static String payload(String request) { return "{\"argv\":[\"/usr/local/deploy/bin/deploy-infrastructure.sh\",\"nexus-broker\"],\"stdinBase64\":\""+Base64.getEncoder().encodeToString(request.getBytes(java.nio.charset.StandardCharsets.UTF_8))+"\"}"; }
    @Test void onlyExactReadOnlyResultQueriesPass() {
        for(String op:List.of("read","exists")) for(String file:List.of("ready","1","restore-2","promotion-3","4-5.check"))
            assertTrue(NexusReadOnlyAgentTask.allowed(payload("{\"operation\":\""+op+"\",\"path\":\"results/"+file+".json\"}")));
    }
    @Test void mutationsTraversalExtraFieldsDuplicateKeysAndMalformedPayloadsFailClosed() {
        for(String raw:List.of(
            "{\"operation\":\"publish\",\"path\":\"results/1.json\"}",
            "{\"operation\":\"publish-exact\",\"path\":\"inbox/1.json\"}",
            "{\"operation\":\"read\",\"path\":\"inbox/1.json\"}",
            "{\"operation\":\"exists\",\"path\":\"results/../1.json\"}",
            "{\"operation\":\"read\",\"path\":\"results/1.json\",\"payload\":{}}",
            "{\"operation\":\"publish\",\"operation\":\"read\",\"path\":\"results/1.json\"}",
            "{\"operation\":\"read\",\"path\":\"results/1.json\"} {}")) assertFalse(NexusReadOnlyAgentTask.allowed(payload(raw)),raw);
        String good=payload("{\"operation\":\"read\",\"path\":\"results/1.json\"}");
        assertFalse(NexusReadOnlyAgentTask.allowed(good.replace("nexus-broker","restart")));
        assertFalse(NexusReadOnlyAgentTask.allowed(good.replace("deploy-infrastructure.sh","other.sh")));
        assertFalse(NexusReadOnlyAgentTask.allowed(good.replace("{\"argv", "{\"extra\":1,\"argv")));
        assertFalse(NexusReadOnlyAgentTask.allowed(good+" {}"));
        assertFalse(NexusReadOnlyAgentTask.allowed("null"));
        assertFalse(NexusReadOnlyAgentTask.allowed("{"));
    }
}
