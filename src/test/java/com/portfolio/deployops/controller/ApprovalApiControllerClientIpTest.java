package com.portfolio.deployops.controller;

import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import com.portfolio.deployops.config.SecurityUserDetails;
import com.portfolio.deployops.dto.ApprovalActionForm;
import com.portfolio.deployops.service.ApprovalService;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;

class ApprovalApiControllerClientIpTest {
    @Test
    void approvalUsesSocketRemoteAddressRatherThanForwardedHeader() {
        ApprovalService service = mock(ApprovalService.class);
        ApprovalApiController controller = new ApprovalApiController(service);
        SecurityUserDetails actor = mock(SecurityUserDetails.class);
        ApprovalActionForm form = new ApprovalActionForm();
        form.setComment("reviewed");
        MockHttpServletRequest request = new MockHttpServletRequest();
        request.setRemoteAddr("198.18.17.1");
        request.addHeader("X-Forwarded-For", "203.0.113.77");
        controller.approve(8L, form, actor, request);
        verify(service).approve(8L, "reviewed", actor, "198.18.17.1", null);
    }

    @Test
    void rejectionUsesSocketRemoteAddressRatherThanForwardedHeader() {
        ApprovalService service = mock(ApprovalService.class);
        ApprovalApiController controller = new ApprovalApiController(service);
        SecurityUserDetails actor = mock(SecurityUserDetails.class);
        ApprovalActionForm form = new ApprovalActionForm();
        form.setComment("review rejected");
        MockHttpServletRequest request = new MockHttpServletRequest();
        request.setRemoteAddr("198.18.17.1");
        request.addHeader("Forwarded", "for=203.0.113.88");
        controller.reject(8L, form, actor, request);
        verify(service).reject(8L, "review rejected", actor, "198.18.17.1", null);
    }

    @Test
    void explicitlyDisplayedStageIsPassedAlongsideTrustedSocketAddress() {
        ApprovalService service=mock(ApprovalService.class);
        var controller=new ApprovalApiController(service);
        var actor=mock(SecurityUserDetails.class);
        var form=new ApprovalActionForm();form.setComment("stage 2");form.setExpectedStepOrder(2);
        var request=new MockHttpServletRequest();request.setRemoteAddr("198.18.17.1");
        request.addHeader("X-Forwarded-For","203.0.113.77");
        controller.approve(8L,form,actor,request);
        verify(service).approve(8L,"stage 2",actor,"198.18.17.1",2);
    }
}
