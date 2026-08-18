package com.example.demo.telegram;

import com.example.demo.auth.TelegramAccountSession;
import com.example.demo.domain.DriveItem;
import com.example.demo.domain.DriveMetadata;
import java.nio.file.Path;
import java.util.Collection;
import java.util.List;

/** Telegram-only operations. Drive rules stay outside this adapter. */
public interface SavedMessagesGateway {

    List<DriveItem> listItems(TelegramAccountSession session);

    DriveItem createFolder(TelegramAccountSession session, DriveMetadata metadata);

    DriveItem uploadFile(TelegramAccountSession session, DriveMetadata metadata, Path stagedFile);

    DriveItem copyItem(TelegramAccountSession session, DriveItem source, DriveMetadata metadata);

    DriveItem updateMetadata(TelegramAccountSession session, DriveItem item, DriveMetadata metadata);

    void deleteItems(TelegramAccountSession session, Collection<DriveItem> items);
}
