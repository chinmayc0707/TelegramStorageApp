package com.example.demo.domain;

import java.util.Objects;

/** Compact, versioned metadata stored in each managed Telegram message caption. */
public record DriveMetadata(
        int version,
        String id,
        String parentId,
        DriveItemType type,
        String name,
        long size,
        String mimeType,
        long modifiedAt
) {
    public static final int CURRENT_VERSION = 1;
    public static final String ROOT_ID = "root";

    public DriveMetadata {
        Objects.requireNonNull(id, "id is required");
        Objects.requireNonNull(parentId, "parentId is required");
        Objects.requireNonNull(type, "type is required");
        Objects.requireNonNull(name, "name is required");
        mimeType = mimeType == null ? "application/octet-stream" : mimeType;
    }

    public DriveItem toDriveItem(long messageId) {
        return new DriveItem(id, parentId, type, name, size, mimeType, messageId, modifiedAt);
    }
}
