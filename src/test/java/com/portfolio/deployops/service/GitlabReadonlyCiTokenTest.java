package com.portfolio.deployops.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.*;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.ObjectMapper;

class GitlabReadonlyCiTokenTest {
    private final SystemSettingService settings = mock(SystemSettingService.class);
    private final SecretCryptoService crypto = mock(SecretCryptoService.class);
    private final GitlabService service = spy(new GitlabService(mock(ObjectMapper.class), settings, crypto));

    @Test void personalPatRemainsPreferredWithoutSystemLookup() throws Exception {
        when(crypto.decryptIfEncrypted("encrypted-personal")).thenReturn("personal-pat");
        assertThat(service.resolveReadonlyCiAccessToken("encrypted-personal")).isEqualTo("personal-pat");
        verifyNoInteractions(settings);
        verify(service, never()).getAuthenticatedUserIdentity(anyString(), any());
    }
    @Test void absentPersonalPatUsesValidatedNonAdminSystemBot() throws Exception {
        when(settings.get("gitlab.system-token.admin-only", "true")).thenReturn("true");
        when(settings.get("gitlab.token", "")).thenReturn("bot-pat");
        doReturn(new GitlabService.GitlabUserIdentity(5L, "deployment-bot", false))
            .when(service).getAuthenticatedUserIdentity("bot-pat", null);
        assertThat(service.resolveReadonlyCiAccessToken(null)).isEqualTo("bot-pat");
        verify(service).resolveAccessToken(null, AutomaticDispatchService.SYSTEM_ACTOR);
    }
    @Test void missingSystemTokenFailsClosed() throws Exception {
        assertThat(service.resolveReadonlyCiAccessToken(null)).isNull();
        verify(service, never()).getAuthenticatedUserIdentity(anyString(), any());
    }
    @Test void adminRootAndMalformedIdentitiesFailClosed() throws Exception {
        when(settings.get("gitlab.token", "")).thenReturn("bot-pat");
        for (var identity : new GitlabService.GitlabUserIdentity[]{
            new GitlabService.GitlabUserIdentity(1, "admin-user", true),
            new GitlabService.GitlabUserIdentity(1, "RoOt", false),
            new GitlabService.GitlabUserIdentity(0, "bot", false),
            new GitlabService.GitlabUserIdentity(5, "", false)}) {
            doReturn(identity).when(service).getAuthenticatedUserIdentity("bot-pat", null);
            assertThat(service.resolveReadonlyCiAccessToken(null)).isNull();
        }
    }
    @Test void identityNetworkFailureFailsClosedWithoutLeakingRemoteException() throws Exception {
        when(settings.get("gitlab.token", "")).thenReturn("bot-pat");
        doThrow(new java.io.IOException("secret-sentinel-token"))
            .when(service).getAuthenticatedUserIdentity("bot-pat", null);
        assertThat(service.resolveReadonlyCiAccessToken(null)).isNull();
    }
    @Test void generalOperatorMutationResolutionStillDoesNotFallback() {
        when(settings.get("gitlab.system-token.admin-only", "true")).thenReturn("true");
        assertThat(service.resolveAccessToken(null, "OPERATOR", "operator")).isNull();
        verify(settings, never()).get("gitlab.token", "");
    }
}
