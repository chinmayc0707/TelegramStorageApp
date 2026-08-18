package com.example.demo.telegram;

import com.example.demo.domain.DriveItem;
import com.example.demo.domain.DriveMetadata;
import java.nio.file.Path;
import java.util.Collection;
import java.util.List;

public interface SavedMessagesGateway {

    @FunctionalInterface
    interface ProgressListener {
        void onProgress(int loadedItems, int scannedMessages, int totalMessages, int percentage);
    }

    default List<DriveItem> listItems() {
        return listItems((loaded, scanned, total, pct) -> {});
    }

    List<DriveItem> listItems(ProgressListener progressListener);

    DriveItem createFolder(DriveMetadata metadata);

    DriveItem uploadFile(DriveMetadata metadata, Path stagedFile);

    DriveItem copyItem(DriveItem source, DriveMetadata metadata);

    DriveItem updateMetadata(DriveItem item, DriveMetadata metadata);

    void deleteItems(Collection<DriveItem> items);

    Path downloadItemFile(DriveItem item);
}
