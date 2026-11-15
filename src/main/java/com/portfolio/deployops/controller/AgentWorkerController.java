package com.portfolio.deployops.controller;

import com.portfolio.deployops.dto.AgentProtocol;
import com.portfolio.deployops.service.AgentQueueService;
import java.io.IOException;
import java.util.Map;
import lombok.RequiredArgsConstructor;
import org.springframework.core.io.FileSystemResource;
import org.springframework.core.io.Resource;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/agent/v1")
@RequiredArgsConstructor
public class AgentWorkerController {
    private final AgentQueueService agents;
    @PostMapping("/enroll")
    public Map<String,Object> enroll(@RequestBody AgentProtocol.Enrollment body) { return agents.enroll(body); }
    @PostMapping("/heartbeat")
    public Map<String,Object> heartbeat(@RequestHeader(value="Authorization",required=false) String authorization,
        @RequestBody AgentProtocol.Heartbeat body) { return agents.heartbeat(authorization,body); }
    @PostMapping("/poll")
    public Map<String,Object> poll(@RequestHeader(value="Authorization",required=false) String authorization) { return agents.poll(authorization); }
    @PostMapping("/tasks/{id}/started")
    public Map<String,Object> started(@RequestHeader(value="Authorization",required=false) String authorization,
        @PathVariable String id) { return agents.started(authorization,id); }
    @PostMapping("/tasks/{id}/result")
    public Map<String,Object> result(@RequestHeader(value="Authorization",required=false) String authorization,
        @PathVariable String id,@RequestBody AgentProtocol.Result body) { return agents.result(authorization,id,body); }
    @GetMapping("/tasks/{id}/artifact")
    public ResponseEntity<Resource> artifact(@RequestHeader(value="Authorization",required=false) String authorization,
        @PathVariable String id) throws IOException {
        var file=agents.artifact(authorization,id);
        return ResponseEntity.ok().contentType(MediaType.APPLICATION_OCTET_STREAM)
            .contentLength(java.nio.file.Files.size(file)).header("Cache-Control","no-store")
            .body(new FileSystemResource(file));
    }
}
