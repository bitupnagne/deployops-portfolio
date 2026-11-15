package com.portfolio.deployops.service;

import com.portfolio.deployops.config.SecurityUserDetails;
import com.portfolio.deployops.domain.ApprovalAction;
import com.portfolio.deployops.domain.ApprovalRecord;
import com.portfolio.deployops.domain.DeployEnvironment;
import com.portfolio.deployops.domain.DeployRequest;
import com.portfolio.deployops.repository.ApprovalRecordRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.web.server.ResponseStatusException;

/** Enforces separation between the PROD requester, approvers, and executor. */
@Service
@RequiredArgsConstructor
public class DeploymentDutySeparationService {

    private final ApprovalRecordRepository approvalRecordRepository;

    public void assertCanOperate(DeployRequest request, SecurityUserDetails operator) {
        if (request == null || request.getEnvironment() != DeployEnvironment.PROD) {
            return;
        }
        if (operator == null || operator.getId() == null) {
            throw forbidden("운영 실행자를 확인할 수 없습니다.");
        }

        Long operatorId = operator.getId();
        if (request.getRequestedBy() != null && operatorId.equals(request.getRequestedBy().getId())) {
            throw forbidden("운영 배포 요청자는 같은 요청을 직접 실행할 수 없습니다. 다른 운영자가 실행해야 합니다.");
        }

        boolean approvedByOperator = approvalRecordRepository.findByDeployRequestId(request.getId()).stream()
            .filter(record -> record.getAction() == ApprovalAction.APPROVED)
            .map(ApprovalRecord::getApprover)
            .anyMatch(approver -> approver != null && operatorId.equals(approver.getId()));
        if (approvedByOperator) {
            throw forbidden("운영 배포 승인자는 같은 요청을 직접 실행할 수 없습니다. 다른 운영자가 실행해야 합니다.");
        }
    }

    private ResponseStatusException forbidden(String message) {
        return new ResponseStatusException(HttpStatus.FORBIDDEN, message);
    }
}
