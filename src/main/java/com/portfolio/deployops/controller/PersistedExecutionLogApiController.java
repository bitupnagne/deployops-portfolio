package com.portfolio.deployops.controller;

import com.portfolio.deployops.config.SecurityUserDetails;
import com.portfolio.deployops.domain.DeployEnvironment;
import com.portfolio.deployops.domain.ProjectPermissionType;
import com.portfolio.deployops.dto.ApiResponse;
import com.portfolio.deployops.repository.RemoteCommandLogRepository;
import com.portfolio.deployops.service.DeployRequestService;
import com.portfolio.deployops.service.ProjectPermissionService;
import com.portfolio.deployops.service.SecretMaskingService;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

@RestController
@RequiredArgsConstructor
public class PersistedExecutionLogApiController {
    private final DeployRequestService requests;
    private final ProjectPermissionService permissions;
    private final RemoteCommandLogRepository logs;
    private final SecretMaskingService masking;

    @GetMapping("/api/v1/operator/logs/{requestId}/persisted")
    public ApiResponse<?> read(@PathVariable Long requestId,
        @RequestParam(defaultValue="20") int limit,
        @AuthenticationPrincipal SecurityUserDetails user) {
        var request=requests.findById(requestId,user);
        var env=request.getEnvironment();
        if(env==null || request.getProject()==null) throw new ResponseStatusException(HttpStatus.CONFLICT,"배포 요청 환경을 확인할 수 없습니다.");
        permissions.assertPermission(user,request.getProject().getId(),
            env==DeployEnvironment.TEST?ProjectPermissionType.TEST_EXECUTE:ProjectPermissionType.PROD_EXECUTE,env);
        int capped=Math.max(1,Math.min(limit,20));
        var records=new ArrayList<>(logs.findBoundedByRequest(requestId,capped));
        Collections.reverse(records);
        List<Map<String,Object>> rows=new ArrayList<>();
        for(var log:records){
            var row=new LinkedHashMap<String,Object>();
            row.put("attemptId",log.getId()); row.put("serverName",safe(log.getServerName(),200));
            row.put("exitCode",log.getExitCode()); row.put("success",log.isSuccess());
            row.put("command",safe(log.getCommandDisplay(),1024));
            row.put("stdout",safe(log.getStdoutText(),16384)); row.put("stderr",safe(log.getStderrText(),16384));
            row.put("truncated",longer(log.getCommandDisplay(),1024)||longer(log.getStdoutText(),16384)||longer(log.getStderrText(),16384));
            rows.add(row);
        }
        return ApiResponse.ok(Map.of("requestId",requestId,"records",rows,"limit",capped));
    }
    private boolean longer(String value,int max){return value!=null && value.length()>max;}
    private String safe(String value,int max){
        String result=masking.mask(value); if(result==null)return "";
        return result.length()>max?result.substring(0,max):result;
    }
}