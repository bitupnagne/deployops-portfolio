package com.portfolio.deployops.service;

import com.fasterxml.jackson.core.JsonParser;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.Base64;
import java.util.Set;

/** Strictly identifies fixed broker file queries, never arbitrary commands or publications. */
final class NexusReadOnlyAgentTask {
    private NexusReadOnlyAgentTask() { }
    static boolean allowed(String payload) {
        try {
            JsonNode task = strict(payload);
            if (!keys(task, Set.of("argv", "stdinBase64"))) return false;
            JsonNode argv = task.get("argv");
            if (!argv.isArray() || argv.size()!=2
                || !argv.get(0).isTextual() || !argv.get(1).isTextual()
                || !"/usr/local/deploy/bin/deploy-infrastructure.sh".equals(argv.get(0).textValue())
                || !"nexus-broker".equals(argv.get(1).textValue())) return false;
            if (!task.get("stdinBase64").isTextual()) return false;
            byte[] decoded = Base64.getDecoder().decode(task.get("stdinBase64").textValue());
            if (decoded.length>8192) return false;
            JsonNode request = strict(new String(decoded, java.nio.charset.StandardCharsets.UTF_8));
            return keys(request, Set.of("operation", "path"))
                && request.get("operation").isTextual() && request.get("path").isTextual()
                && Set.of("read", "exists").contains(request.get("operation").textValue())
                && request.get("path").textValue().matches("results/(ready|(?:restore-|promotion-)?[1-9][0-9]*(?:-[1-9][0-9]*\\.check)?)\\.json");
        } catch (Exception rejected) { return false; }
    }
    private static JsonNode strict(String input) throws Exception {
        if (input==null || input.length()>16384) throw new IllegalArgumentException();
        ObjectMapper mapper = new ObjectMapper();
        try (JsonParser parser=mapper.getFactory().createParser(input)) {
            parser.enable(JsonParser.Feature.STRICT_DUPLICATE_DETECTION);
            JsonNode node=mapper.readTree(parser);
            if (parser.nextToken()!=null) throw new IllegalArgumentException();
            return node;
        }
    }
    private static boolean keys(JsonNode node, Set<String> expected) {
        if (node==null || !node.isObject() || node.size()!=expected.size()) return false;
        var names=node.fieldNames();
        while(names.hasNext()) if(!expected.contains(names.next())) return false;
        return true;
    }
}
