package com.example.demo.config;

import java.nio.file.Path;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

@Component
@ConfigurationProperties(prefix = "telegram.drive")
public class TelegramDriveProperties {

    private Path sessionRoot = Path.of("./telegram-drive-data/sessions");
    private Path uploadRoot = Path.of("./telegram-drive-data/staging");

    public Path getSessionRoot() {
        return sessionRoot;
    }

    public void setSessionRoot(Path sessionRoot) {
        this.sessionRoot = sessionRoot;
    }

    public Path getUploadRoot() {
        return uploadRoot;
    }

    public void setUploadRoot(Path uploadRoot) {
        this.uploadRoot = uploadRoot;
    }
}
