package com.portfolio.deployops.controller;

import static org.assertj.core.api.Assertions.assertThat;
import com.portfolio.deployops.dto.NginxPolicyForm;
import org.junit.jupiter.api.Test;
import org.springframework.security.access.prepost.PreAuthorize;

class NginxEditorAuthorizationTest {
    @Test void approverCanReadConfigurationButServerHealthMutationRemainsOperatorOnly() throws Exception {
        for (var method : new java.lang.reflect.Method[]{
            NginxChangeApiController.class.getMethod("preview",NginxPolicyForm.class),
            NginxChangeApiController.class.getMethod("requestedConfiguration",Long.class),
            NginxChangeApiController.class.getMethod("currentConfiguration",Long.class,String.class)}) {
            assertThat(method.getAnnotation(PreAuthorize.class).value()).isEqualTo("hasAnyRole('OPERATOR','APPROVER','SUPER_ADMIN')");
        }
        for (var method : ServerApiController.class.getDeclaredMethods()) {
            if (method.getName().equals("check") || method.getName().equals("checkAll"))
                assertThat(method.getAnnotation(PreAuthorize.class).value()).isEqualTo("hasAnyRole('OPERATOR','SUPER_ADMIN')");
        }
    }
}
