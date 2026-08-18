package com.example.demo.telegram;

import com.example.demo.auth.TelegramAccountSession;
import com.example.demo.config.TelegramDriveProperties;
import com.example.demo.domain.CaptionCodec;
import com.example.demo.domain.DriveItem;
import com.example.demo.domain.DriveMetadata;
import com.example.demo.web.ApiException;
import it.tdlight.client.SimpleTelegramClient;
import it.tdlight.jni.TdApi;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.TimeUnit;
import org.springframework.stereotype.Service;

@Service
public class TdlightSavedMessagesGateway implements SavedMessagesGateway {

    private static final Duration TELEGRAM_TIMEOUT = Duration.ofMinutes(10);
    private static final TdApi.TextEntity[] NO_ENTITIES = new TdApi.TextEntity[0];

    private final CaptionCodec captionCodec;
    private final Path markerFile;

    public TdlightSavedMessagesGateway(CaptionCodec captionCodec, TelegramDriveProperties properties) {
        this.captionCodec = captionCodec;
        this.markerFile = properties.getUploadRoot().toAbsolutePath().normalize().resolve(".tgdrive-folder-marker");
    }

    @Override
    public List<DriveItem> listItems(TelegramAccountSession session) {
        SimpleTelegramClient client = session.client();
        long chatId = savedMessagesChatId(client);
        Map<String, DriveItem> newestByLogicalId = new HashMap<>();
        Set<Long> seenMessages = new HashSet<>();
        long fromMessageId = 0;

        for (int pageNumber = 0; pageNumber < 10_000; pageNumber++) {
            TdApi.Messages page = await(client.send(new TdApi.GetChatHistory(chatId, fromMessageId, 0, 100, false)));
            if (page.messages == null || page.messages.length == 0) {
                break;
            }
            long oldestId = fromMessageId;
            int newlySeen = 0;
            for (TdApi.Message message : page.messages) {
                if (!seenMessages.add(message.id)) {
                    continue;
                }
                newlySeen++;
                oldestId = message.id;
                itemFromMessage(message).ifPresent(item -> newestByLogicalId.merge(item.id(), item,
                        (existing, candidate) -> candidate.modifiedAt() >= existing.modifiedAt() ? candidate : existing));
            }
            // TDLib can return fewer than the requested messages even when older history remains.
            // Stop only after it no longer advances the history cursor.
            if (newlySeen == 0 || oldestId == fromMessageId) {
                break;
            }
            fromMessageId = oldestId;
        }

        return newestByLogicalId.values().stream()
                .sorted(Comparator.comparing(DriveItem::isFolder).reversed().thenComparing(item -> item.name().toLowerCase()))
                .toList();
    }

    @Override
    public DriveItem createFolder(TelegramAccountSession session, DriveMetadata metadata) {
        ensureMarkerFile();
        return sendDocument(session, metadata, markerFile);
    }

    @Override
    public DriveItem uploadFile(TelegramAccountSession session, DriveMetadata metadata, Path stagedFile) {
        return sendDocument(session, metadata, stagedFile);
    }

    @Override
    public DriveItem copyItem(TelegramAccountSession session, DriveItem source, DriveMetadata metadata) {
        SimpleTelegramClient client = session.client();
        long chatId = savedMessagesChatId(client);
        TdApi.Messages copied = await(client.send(new TdApi.ForwardMessages(
                chatId, null, chatId, new long[]{source.messageId()}, null, true, false)));
        if (copied.messages == null || copied.messages.length == 0) {
            throw ApiException.serviceUnavailable("Telegram did not return the copied item.");
        }
        DriveItem copiedItem = metadata.toDriveItem(copied.messages[0].id);
        return updateMetadata(session, copiedItem, metadata);
    }

    @Override
    public DriveItem updateMetadata(TelegramAccountSession session, DriveItem item, DriveMetadata metadata) {
        SimpleTelegramClient client = session.client();
        long chatId = savedMessagesChatId(client);
        TdApi.FormattedText caption = formatted(captionCodec.encode(metadata));
        TdApi.Message updated = await(client.send(new TdApi.EditMessageCaption(chatId, item.messageId(), null, caption, false)));
        return metadata.toDriveItem(updated.id);
    }

    @Override
    public void deleteItems(TelegramAccountSession session, Collection<DriveItem> items) {
        if (items.isEmpty()) {
            return;
        }
        SimpleTelegramClient client = session.client();
        long chatId = savedMessagesChatId(client);
        List<Long> messageIds = items.stream().map(DriveItem::messageId).toList();
        for (int start = 0; start < messageIds.size(); start += 100) {
            int end = Math.min(start + 100, messageIds.size());
            long[] batch = new long[end - start];
            for (int index = start; index < end; index++) {
                batch[index - start] = messageIds.get(index);
            }
            await(client.send(new TdApi.DeleteMessages(chatId, batch, true)));
        }
    }

    private DriveItem sendDocument(TelegramAccountSession session, DriveMetadata metadata, Path file) {
        SimpleTelegramClient client = session.client();
        long chatId = savedMessagesChatId(client);
        TdApi.InputMessageDocument content = new TdApi.InputMessageDocument(
                new TdApi.InputFileLocal(file.toAbsolutePath().toString()),
                null,
                true,
                formatted(captionCodec.encode(metadata))
        );
        TdApi.Message message = await(client.sendMessage(new TdApi.SendMessage(chatId, null, null, null, null, content), true));
        return metadata.toDriveItem(message.id);
    }

    private long savedMessagesChatId(SimpleTelegramClient client) {
        TdApi.User me = await(client.getMeAsync());
        return await(client.send(new TdApi.CreatePrivateChat(me.id, false))).id;
    }

    private java.util.Optional<DriveItem> itemFromMessage(TdApi.Message message) {
        if (!(message.content instanceof TdApi.MessageDocument document) || document.caption == null) {
            return java.util.Optional.empty();
        }
        return captionCodec.decode(document.caption.text).map(metadata -> metadata.toDriveItem(message.id));
    }

    private TdApi.FormattedText formatted(String value) {
        return new TdApi.FormattedText(value, NO_ENTITIES);
    }

    private void ensureMarkerFile() {
        try {
            Files.createDirectories(markerFile.getParent());
            if (Files.notExists(markerFile)) {
                Files.write(markerFile, new byte[]{0});
            }
        } catch (IOException exception) {
            throw new IllegalStateException("Unable to create the internal folder marker", exception);
        }
    }

    private <T> T await(java.util.concurrent.CompletableFuture<T> future) {
        try {
            return future.get(TELEGRAM_TIMEOUT.toSeconds(), TimeUnit.SECONDS);
        } catch (Exception exception) {
            Throwable cause = exception.getCause() == null ? exception : exception.getCause();
            String detail = cause.getMessage() == null ? "Telegram did not complete the request." : cause.getMessage();
            throw ApiException.serviceUnavailable("Telegram request failed: " + detail);
        }
    }
}
