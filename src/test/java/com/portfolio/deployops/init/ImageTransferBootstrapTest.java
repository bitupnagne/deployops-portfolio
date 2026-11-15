package com.portfolio.deployops.init;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

import com.portfolio.deployops.domain.SystemSetting;
import com.portfolio.deployops.repository.SystemSettingRepository;
import com.portfolio.deployops.service.RemoteDeployService;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.NullAndEmptySource;
import org.junit.jupiter.params.provider.ValueSource;
import org.mockito.ArgumentCaptor;

class ImageTransferBootstrapTest {
    private final SystemSettingRepository repository = mock(SystemSettingRepository.class);

    @ParameterizedTest
    @NullAndEmptySource
    @ValueSource(strings = "   ")
    void unsetRetainsLegacyBehavior(String mode) {
        new ImageTransferBootstrap(repository, mode).run(null);
        verifyNoInteractions(repository);
    }

    @ParameterizedTest
    @ValueSource(strings = {"invalid", "PUSH", "push\nregistry"})
    void invalidModeFailsBeforePersistence(String mode) {
        assertThatThrownBy(() -> new ImageTransferBootstrap(repository, mode).run(null))
            .isInstanceOf(IllegalArgumentException.class);
        verifyNoInteractions(repository);
    }

    @ParameterizedTest
    @ValueSource(strings = {"registry", "push"})
    void existingSettingIsNeverOverwritten(String existing) {
        when(repository.findById(RemoteDeployService.IMAGE_TRANSFER_SETTING))
            .thenReturn(Optional.of(SystemSetting.builder().settingValue(existing).build()));
        new ImageTransferBootstrap(repository, existing.equals("registry") ? "push" : "registry").run(null);
        verify(repository, never()).insert(any());
        verify(repository, never()).update(any());
    }

    @ParameterizedTest
    @ValueSource(strings = {"push", "registry"})
    void explicitFreshModeIsInsertedOnce(String mode) {
        when(repository.findById(RemoteDeployService.IMAGE_TRANSFER_SETTING)).thenReturn(Optional.empty());
        new ImageTransferBootstrap(repository, mode).run(null);
        ArgumentCaptor<SystemSetting> captor = ArgumentCaptor.forClass(SystemSetting.class);
        verify(repository).insert(captor.capture());
        assertThat(captor.getValue().getSettingKey()).isEqualTo("deploy.image.transfer");
        assertThat(captor.getValue().getSettingValue()).isEqualTo(mode);
        assertThat(captor.getValue().getCategory()).isEqualTo("integration");
        verify(repository, never()).update(any());
    }

    @Test
    void nextStartupIsIdempotentAndIndependentOfUserBootstrap() {
        AtomicReference<SystemSetting> stored = new AtomicReference<>();
        when(repository.findById(RemoteDeployService.IMAGE_TRANSFER_SETTING))
            .thenAnswer(invocation -> Optional.ofNullable(stored.get()));
        when(repository.insert(any())).thenAnswer(invocation -> {
            stored.set(invocation.getArgument(0));
            return 1;
        });
        new ImageTransferBootstrap(repository, "push").run(null);
        new ImageTransferBootstrap(repository, "registry").run(null);
        verify(repository, times(1)).insert(any());
        assertThat(stored.get().getSettingValue()).isEqualTo("push");
    }
}
