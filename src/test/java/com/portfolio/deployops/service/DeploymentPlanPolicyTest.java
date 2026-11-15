package com.portfolio.deployops.service;

import com.portfolio.deployops.domain.*;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import static org.assertj.core.api.Assertions.*;

class DeploymentPlanPolicyTest {
    @Test void oneWasAndOneWebPlusInactiveDuplicatesRemainSupported() {
        var project=Project.builder().components(List.of(
            DeployComponent.builder().role(ServerType.WAS).active(true).build(),
            DeployComponent.builder().role(ServerType.WEB).active(true).build(),
            DeployComponent.builder().role(ServerType.WAS).active(false).build())).build();
        assertThatCode(() -> DeploymentPlanPolicy.assertComponentRoles(project,HttpStatus.BAD_REQUEST))
            .doesNotThrowAnyException();
    }
    @Test void manualStopAndAutomaticRollbackRemainSupportedInSelectedEnvironment() {
        var prodRollback=ServerGroup.builder().environment(ServerEnvironment.PROD).failurePolicy(GroupFailurePolicy.ROLLBACK).build();
        var testStop=ServerGroup.builder().environment(ServerEnvironment.TEST).failurePolicy(GroupFailurePolicy.STOP).build();
        assertThatCode(() -> DeploymentPlanPolicy.assertStrategy(List.of(prodRollback,testStop),
            DeployEnvironment.TEST,DeployStrategy.MANUAL,HttpStatus.BAD_REQUEST)).doesNotThrowAnyException();
        for (var strategy : List.of(DeployStrategy.SAFE,DeployStrategy.WAVE))
            assertThatCode(() -> DeploymentPlanPolicy.assertStrategy(List.of(prodRollback),
                DeployEnvironment.PROD,strategy,HttpStatus.BAD_REQUEST)).doesNotThrowAnyException();
        assertThatThrownBy(() -> DeploymentPlanPolicy.assertStrategy(List.of(prodRollback),
            DeployEnvironment.PROD,DeployStrategy.MANUAL,HttpStatus.CONFLICT)).hasMessageContaining("ROLLBACK");
    }
}
