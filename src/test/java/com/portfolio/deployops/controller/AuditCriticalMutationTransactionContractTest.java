package com.portfolio.deployops.controller;

import static org.assertj.core.api.Assertions.assertThat;

import com.portfolio.deployops.config.SecurityUserDetails;
import com.portfolio.deployops.dto.PolicyForm;
import com.portfolio.deployops.dto.ProjectForm;
import com.portfolio.deployops.dto.ServerForm;
import com.portfolio.deployops.dto.ServerGroupForm;
import com.portfolio.deployops.dto.TeamForm;
import com.portfolio.deployops.dto.UserForm;
import java.lang.reflect.Method;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.springframework.transaction.annotation.Transactional;

class AuditCriticalMutationTransactionContractTest {

    @Test
    void auditedControlMutationsKeepTheirDatabaseChangeAndAuditInOneTransaction() throws Exception {
        assertTransactional(UserApiController.class, "create", UserForm.class);
        assertTransactional(UserApiController.class, "update", Long.class, UserForm.class);
        assertTransactional(UserApiController.class, "deactivate", Long.class);
        assertTransactional(UserApiController.class, "setGitlabUsername", Long.class, Map.class);
        assertTransactional(UserApiController.class, "resetPassword", Long.class, Map.class,
            SecurityUserDetails.class);

        assertTransactional(ProjectApiController.class, "create", ProjectForm.class, SecurityUserDetails.class);
        assertTransactional(ProjectApiController.class, "update", Long.class, ProjectForm.class, SecurityUserDetails.class);
        assertTransactional(ProjectApiController.class, "delete", Long.class);

        assertTransactional(PolicyApiController.class, "create", PolicyForm.class);
        assertTransactional(PolicyApiController.class, "update", Long.class, PolicyForm.class);
        assertTransactional(PolicyApiController.class, "delete", Long.class);

        assertTransactional(SettingsApiController.class, "save", String.class, Map.class);
        assertTransactional(SettingsApiController.class, "migratePlainSecrets");
        assertTransactional(SettingsApiController.class, "rotateSecretsToCurrentKey");

        assertTransactional(ServerApiController.class, "create", ServerForm.class);
        assertTransactional(ServerApiController.class, "update", Long.class, ServerForm.class);
        assertTransactional(ServerApiController.class, "delete", Long.class);

        assertTransactional(ServerGroupApiController.class, "create", ServerGroupForm.class);
        assertTransactional(ServerGroupApiController.class, "update", Long.class, ServerGroupForm.class);
        assertTransactional(ServerGroupApiController.class, "delete", Long.class);
        assertTransactional(ServerGroupApiController.class, "reorder", Map.class);

        assertTransactional(TeamApiController.class, "create", TeamForm.class);
        assertTransactional(TeamApiController.class, "update", Long.class, TeamForm.class);
        assertTransactional(TeamApiController.class, "delete", Long.class);

        assertTransactional(DeployComponentApiController.class, "create", Long.class,
            com.portfolio.deployops.dto.DeployComponentForm.class);
        assertTransactional(DeployComponentApiController.class, "update", Long.class, Long.class,
            com.portfolio.deployops.dto.DeployComponentForm.class);
        assertTransactional(DeployComponentApiController.class, "delete", Long.class, Long.class);
    }

    private void assertTransactional(Class<?> owner, String methodName, Class<?>... parameterTypes) throws Exception {
        Method method = owner.getMethod(methodName, parameterTypes);
        assertThat(method.getAnnotation(Transactional.class))
            .as("%s#%s must share its control mutation transaction with required audit persistence",
                owner.getSimpleName(), methodName)
            .isNotNull();
    }
}
