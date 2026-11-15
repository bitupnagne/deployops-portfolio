package com.portfolio.deployops.controller;

import static org.assertj.core.api.Assertions.assertThat;

import com.portfolio.deployops.config.SecurityUserDetails;
import com.portfolio.deployops.dto.NginxPolicyForm;
import java.lang.reflect.Method;
import org.junit.jupiter.api.Test;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.multipart.MultipartFile;

class NginxChangeAuthorizationTest {
    @Test
    void requestAndApprovalRolesRemainSeparated() throws Exception {
        Method create = NginxChangeApiController.class.getMethod("create", NginxPolicyForm.class,
            MultipartFile.class, MultipartFile.class, SecurityUserDetails.class);
        Method approve = NginxChangeApiController.class.getMethod("approve", Long.class, SecurityUserDetails.class);
        Method rollback = NginxChangeApiController.class.getMethod("requestRollback", Long.class, SecurityUserDetails.class);

        assertThat(create.getAnnotation(PreAuthorize.class).value())
            .isEqualTo("hasAnyRole('OPERATOR','SUPER_ADMIN')");
        assertThat(approve.getAnnotation(PreAuthorize.class).value())
            .isEqualTo("hasAnyRole('APPROVER','SUPER_ADMIN')");
        assertThat(rollback.getAnnotation(PreAuthorize.class).value())
            .isEqualTo("hasAnyRole('OPERATOR','SUPER_ADMIN')");
    }
}
