package com.example.demo.domain;

import java.util.Objects;

public record DriveItem(
        String id,
        String parentId,
        DriveItemType type,
        String name,
        long size,
        String mimeType,
        long messageId,
        long modifiedAt
) {
    public DriveItem {
        Objects.requireNonNull(id, "id is required");
        Objects.requireNonNull(parentId, "parentId is required");
        Objects.requireNonNull(type, "type is required");
        Objects.requireNonNull(name, "name is required");
        mimeType = mimeType == null ? "application/octet-stream" : mimeType;
    }

    public boolean isFolder() {
        return type == DriveItemType.FOLDER;
    }
}
