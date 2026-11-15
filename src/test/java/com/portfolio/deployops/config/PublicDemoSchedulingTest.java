package com.portfolio.deployops.config;

import static org.assertj.core.api.Assertions.assertThat;

import com.portfolio.deployops.service.AutomaticDispatchService;
import com.portfolio.deployops.service.BootRecoveryService;
import com.portfolio.deployops.service.StuckDeploymentReconciler;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.scheduling.annotation.ScheduledAnnotationBeanPostProcessor;

class PublicDemoSchedulingTest {
    private final ApplicationContextRunner context = new ApplicationContextRunner()
        .withUserConfiguration(OperationsSchedulingConfig.class, PublicDemoAccessFilter.class);

    @Test
    void publicDemoRegistersGuardWithoutScheduledOperations() {
        context.withPropertyValues("app.public-demo.enabled=true").run(app -> {
            assertThat(app).hasSingleBean(PublicDemoAccessFilter.class);
            assertThat(app).doesNotHaveBean(ScheduledAnnotationBeanPostProcessor.class);
        });
    }

    @Test
    void normalInstallationKeepsExistingSchedulingAndDoesNotInstallDemoGuard() {
        context.run(app -> {
            assertThat(app).doesNotHaveBean(PublicDemoAccessFilter.class);
            assertThat(app).hasSingleBean(ScheduledAnnotationBeanPostProcessor.class);
        });
    }

    @Test
    void publicDemoDoesNotCreateStartupRecoveryOrDeploymentEventWorkers() {
        context.withUserConfiguration(AutomaticDispatchService.class, BootRecoveryService.class,
                StuckDeploymentReconciler.class)
            .withPropertyValues("app.public-demo.enabled=true").run(app -> {
                assertThat(app).doesNotHaveBean(AutomaticDispatchService.class);
                assertThat(app).doesNotHaveBean(BootRecoveryService.class);
                assertThat(app).doesNotHaveBean(StuckDeploymentReconciler.class);
            });
    }
}
