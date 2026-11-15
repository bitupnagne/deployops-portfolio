package com.portfolio.deployops.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.spy;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.when;

import org.junit.jupiter.api.Test;
import tools.jackson.databind.ObjectMapper;

class GitlabServiceTest {

    private final SystemSettingService settings = mock(SystemSettingService.class);
    private final SecretCryptoService secretCryptoService = mock(SecretCryptoService.class);
    private final GitlabService service = new GitlabService(
        mock(ObjectMapper.class), settings, secretCryptoService);

    @Test
    void systemAutomationRoleMayUseConfiguredSystemTokenEvenInAdminOnlyMode() {
        when(settings.get("gitlab.system-token.admin-only", "true")).thenReturn("true");
        when(settings.get("gitlab.token", "")).thenReturn("system-token");

        GitlabService.TokenResolution resolution = service.resolveAccessTokenInfo(
            null, AutomaticDispatchService.SYSTEM_ACTOR, null);

        assertThat(resolution.token()).isEqualTo("system-token");
        assertThat(resolution.source()).isEqualTo("SYSTEM_BOT");
    }

    @Test
    void ordinaryRoleStillCannotUseAdminOnlySystemToken() {
        when(settings.get("gitlab.system-token.admin-only", "true")).thenReturn("true");

        GitlabService.TokenResolution resolution = service.resolveAccessTokenInfo(
            null, "OPERATOR", null);

        assertThat(resolution.token()).isNull();
        assertThat(resolution.source()).isEqualTo("NONE");
    }

    @Test
    void rootSystemTokenCannotMergeOrStartAnImmutableBuild() throws Exception {
        when(settings.get("gitlab.token", "")).thenReturn("system-token");
        GitlabService guarded = spy(service);
        doReturn(new GitlabService.GitlabUserIdentity(1L, "root", true))
            .when(guarded).getAuthenticatedUserIdentity("system-token", null);

        assertThatThrownBy(() -> guarded.mergeMergeRequest(
            "system-token", null, 101L, 4L,
            "0123456789abcdef0123456789abcdef01234567", true))
            .isInstanceOf(IllegalArgumentException.class)
            .hasMessageContaining("root/admin");
        assertThatThrownBy(() -> guarded.ensureImmutableTag(
            "system-token", null, 101L, "deploy/build-4",
            "0123456789abcdef0123456789abcdef01234567"))
            .isInstanceOf(IllegalArgumentException.class)
            .hasMessageContaining("root/admin");
    }

    @Test
    void approvalIdentityCheckAllowsPersonalPatWhenNoSystemTokenConfigured() throws Exception {
        GitlabService guarded = spy(service);
        guarded.assertDistinctApprovalActor(
            "personal-token", new GitlabService.GitlabUserIdentity(7L, "reviewer"));
        verify(guarded, never()).getAuthenticatedUserIdentity("personal-token", null);
    }

    @Test
    void approvalIdentityCheckRejectsExactSystemPatWithoutRemoteLookup() throws Exception {
        when(settings.get("gitlab.token", "")).thenReturn("system-token");
        GitlabService guarded = spy(service);

        assertThatThrownBy(() -> guarded.assertDistinctApprovalActor(
            "system-token", new GitlabService.GitlabUserIdentity(7L, "reviewer")))
            .isInstanceOf(GitlabService.SharedApprovalActorException.class)
            .hasMessageContaining("연동 계정과 다른");
        verify(guarded, never()).getAuthenticatedUserIdentity("system-token", null);
    }

    @Test
    void approvalIdentityCheckRejectsDifferentPatOfSameSystemActor() throws Exception {
        when(settings.get("gitlab.token", "")).thenReturn("system-token");
        GitlabService guarded = spy(service);
        doReturn(new GitlabService.GitlabUserIdentity(7L, "system-bot"))
            .when(guarded).getAuthenticatedUserIdentity("system-token", null);

        assertThatThrownBy(() -> guarded.assertDistinctApprovalActor(
            "different-token", new GitlabService.GitlabUserIdentity(7L, "system-bot")))
            .isInstanceOf(GitlabService.SharedApprovalActorException.class)
            .hasMessageContaining("연동 계정과 같습니다");
    }

    @Test
    void approvalIdentityCheckFailsClosedWhenConfiguredSystemActorCannotBeRead() throws Exception {
        when(settings.get("gitlab.token", "")).thenReturn("system-token");
        GitlabService guarded = spy(service);
        org.mockito.Mockito.doThrow(new java.io.IOException("unavailable"))
            .when(guarded).getAuthenticatedUserIdentity("system-token", null);

        assertThatThrownBy(() -> guarded.assertDistinctApprovalActor(
            "personal-token", new GitlabService.GitlabUserIdentity(7L, "reviewer")))
            .isInstanceOf(IllegalStateException.class)
            .hasMessageContaining("확인할 수 없어");
    }
}
