package com.portfolio.deployops.controller;

import com.portfolio.deployops.config.SecurityUserDetails;
import com.portfolio.deployops.domain.AuditResult;
import com.portfolio.deployops.domain.ProjectImageHistory;
import com.portfolio.deployops.domain.ProjectPermissionType;
import com.portfolio.deployops.dto.ApiResponse;
import com.portfolio.deployops.service.AuditLogService;
import com.portfolio.deployops.service.ProjectImageHistoryService;
import com.portfolio.deployops.service.ProjectPermissionService;
import jakarta.servlet.http.HttpServletRequest;
import java.nio.charset.StandardCharsets;
import java.util.Map;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1/project-images")
@RequiredArgsConstructor
public class ProjectImageApiController {

    private final ProjectImageHistoryService projectImageHistoryService;
    private final AuditLogService auditLogService;
    private final ProjectPermissionService projectPermissionService;

    @GetMapping("/{id}/delivery-guide")
    public ApiResponse<?> deliveryGuide(
        @PathVariable Long id,
        @AuthenticationPrincipal SecurityUserDetails currentUser,
        HttpServletRequest httpRequest
    ) {
        ProjectImageHistory history = projectImageHistoryService.findById(id);
        assertProjectView(currentUser, history);
        auditImageAccess(currentUser, history, "Image delivery guide viewed", httpRequest);
        return ApiResponse.ok(Map.of(
            "imageReference", projectImageHistoryService.buildImageReference(history),
            "dockerPullCommand", projectImageHistoryService.buildPullCommand(history),
            "dockerSaveCommand", projectImageHistoryService.buildSaveCommand(history)
        ));
    }

    @GetMapping("/{id}/delivery-script")
    public ResponseEntity<byte[]> deliveryScript(
        @PathVariable Long id,
        @AuthenticationPrincipal SecurityUserDetails currentUser,
        HttpServletRequest httpRequest
    ) {
        ProjectImageHistory history = projectImageHistoryService.findById(id);
        assertProjectView(currentUser, history);
        byte[] body = projectImageHistoryService.buildDeliveryGuide(history).getBytes(StandardCharsets.UTF_8);

        auditImageAccess(currentUser, history, "Image delivery guide downloaded", httpRequest);

        return ResponseEntity.ok()
            .header(HttpHeaders.CONTENT_DISPOSITION, "attachment; filename=\"image-delivery-" + id + ".txt\"")
            .contentType(new MediaType("text", "plain", StandardCharsets.UTF_8))
            .body(body);
    }

    private void auditImageAccess(
        SecurityUserDetails currentUser,
        ProjectImageHistory history,
        String action,
        HttpServletRequest httpRequest
    ) {
        String actor = currentUser != null ? currentUser.getName() : "anonymous";
        String detail = "projectId=" + (history.getProject() != null ? history.getProject().getId() : "-")
            + ", historyId=" + history.getId()
            + ", imageTag=" + history.getImageTag()
            + ", imageDigest=" + history.getImageDigest();

        auditLogService.record(
            actor,
            action,
            "ProjectImageHistory",
            String.valueOf(history.getId()),
            detail,
            AuditResult.SUCCESS,
            httpRequest.getRemoteAddr()
        );
    }

    private void assertProjectView(SecurityUserDetails currentUser, ProjectImageHistory history) {
        Long projectId = history.getProject() != null ? history.getProject().getId() : null;
        projectPermissionService.assertPermission(currentUser, projectId, ProjectPermissionType.PROJECT_VIEW);
    }
}
