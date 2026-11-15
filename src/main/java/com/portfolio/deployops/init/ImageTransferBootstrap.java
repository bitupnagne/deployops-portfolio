package com.portfolio.deployops.init;

import com.portfolio.deployops.domain.SystemSetting;
import com.portfolio.deployops.repository.SystemSettingRepository;
import com.portfolio.deployops.service.RemoteDeployService;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.stereotype.Component;

@Component
public class ImageTransferBootstrap implements ApplicationRunner {
    private final SystemSettingRepository repository;
    private final String configuredMode;

    public ImageTransferBootstrap(SystemSettingRepository repository,
            @Value("${app.bootstrap.image-transfer-mode:}") String configuredMode) {
        this.repository = repository;
        this.configuredMode = configuredMode;
    }

    @Override
    public void run(ApplicationArguments args) {
        String mode = configuredMode == null ? "" : configuredMode.trim();
        if (mode.isEmpty()) {
            return;
        }
        if (!mode.equals("push") && !mode.equals("registry")) {
            throw new IllegalArgumentException("초기 이미지 전달 방식은 push 또는 registry여야 합니다.");
        }
        // 기존 통합설정은 설치 프로파일이나 다음 기동에서 덮어쓰지 않는다.
        if (repository.findById(RemoteDeployService.IMAGE_TRANSFER_SETTING).isEmpty()) {
            repository.insert(SystemSetting.builder()
                .settingKey(RemoteDeployService.IMAGE_TRANSFER_SETTING)
                .settingValue(mode)
                .category("integration")
                .build());
        }
    }
}
