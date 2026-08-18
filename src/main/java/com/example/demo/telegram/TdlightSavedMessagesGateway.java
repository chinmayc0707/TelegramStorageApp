package com.example.demo.telegram;

import com.example.demo.Login;
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
import java.util.*;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

@Service
public class TdlightSavedMessagesGateway implements SavedMessagesGateway {

    private static final Logger log = LoggerFactory.getLogger(TdlightSavedMessagesGateway.class);
    private static final Duration TELEGRAM_TIMEOUT = Duration.ofMinutes(5);

    private final Login login;
    private final CaptionCodec captionCodec;
    private final Path markerFile;

    public TdlightSavedMessagesGateway(Login login, CaptionCodec captionCodec, TelegramDriveProperties properties) {
        this.login = login;
        this.captionCodec = captionCodec;
        Path root = properties.getUploadRoot().toAbsolutePath().normalize();
        this.markerFile = root.resolve(".tgdrive-folder-marker");
        try {
            Files.createDirectories(root);
            if (!Files.exists(markerFile)) {
                Files.writeString(markerFile, "TeleDrive folder marker");
            }
        } catch (IOException e) {
            log.error("Failed to initialize staging upload directory", e);
        }
    }

    private SimpleTelegramClient requireClient() {
        SimpleTelegramClient client = login.getClient();
        if (client == null || !"READY".equalsIgnoreCase(login.getCurrentStatus())) {
            throw ApiException.unauthorized("Please sign in to Telegram to access your drive.");
        }
        return client;
    }

    @Override
    public List<DriveItem> listItems(ProgressListener progressListener) {
        SimpleTelegramClient client = requireClient();
        long chatId = savedMessagesChatId(client);
        Map<String, DriveItem> newestByLogicalId = new HashMap<>();
        Set<Long> seenMessages = new HashSet<>();
        long fromMessageId = 0;

        int totalMessages = 0;
        try {
            TdApi.Count messageCount = await(client.send(new TdApi.GetChatMessageCount(chatId, null, new TdApi.SearchMessagesFilterEmpty(), false)));
            if (messageCount != null && messageCount.count > 0) {
                totalMessages = messageCount.count;
            }
        } catch (Exception e) {
            log.warn("Could not retrieve message count from Saved Messages: {}", e.getMessage());
        }

        if (progressListener != null) {
            progressListener.onProgress(0, 0, totalMessages, 0);
        }

        int totalScanned = 0;

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

            totalScanned += newlySeen;

            if (progressListener != null) {
                int percent;
                if (totalMessages > 0) {
                    percent = Math.min(95, (int) Math.round(((double) totalScanned / totalMessages) * 100));
                } else {
                    percent = Math.min(90, (pageNumber + 1) * 15);
                }
                progressListener.onProgress(newestByLogicalId.size(), totalScanned, totalMessages > 0 ? totalMessages : totalScanned, percent);
            }

            if (newlySeen == 0 || oldestId == fromMessageId) {
                break;
            }
            fromMessageId = oldestId;
        }

        if (progressListener != null) {
            progressListener.onProgress(newestByLogicalId.size(), totalScanned, totalMessages > 0 ? totalMessages : totalScanned, 100);
        }

        return newestByLogicalId.values().stream()
                .sorted(Comparator.comparing(DriveItem::isFolder).reversed().thenComparing(item -> item.name().toLowerCase()))
                .toList();
    }

    @Override
    public DriveItem createFolder(DriveMetadata metadata) {
        ensureMarkerFile();
        return sendDocument(metadata, markerFile);
    }

    @Override
    public DriveItem uploadFile(DriveMetadata metadata, Path stagedFile) {
        return sendDocument(metadata, stagedFile);
    }

    @Override
    public DriveItem copyItem(DriveItem source, DriveMetadata metadata) {
        SimpleTelegramClient client = requireClient();
        long chatId = savedMessagesChatId(client);

        // Fetch the original message to get the Telegram file ID
        TdApi.Message original = await(client.send(new TdApi.GetMessage(chatId, source.messageId())));
        if (!(original.content instanceof TdApi.MessageDocument originalDoc)) {
            throw ApiException.badRequest("Only file messages can be copied.");
        }

        // Re-upload using InputFileId — this reuses the already-uploaded file
        // without needing to re-transmit any bytes, and works reliably in self-chat.
        TdApi.InputFileId inputFileId = new TdApi.InputFileId();
        inputFileId.id = originalDoc.document.document.id;

        TdApi.InputDocument inputDoc = new TdApi.InputDocument();
        inputDoc.document = inputFileId;

        TdApi.InputMessageDocument content = new TdApi.InputMessageDocument();
        content.document = inputDoc;
        content.caption = formatted(captionCodec.encode(metadata));

        TdApi.Message message = await(client.sendMessage(
                new TdApi.SendMessage(chatId, null, null, null, null, content), true));
        return metadata.toDriveItem(message.id);
    }

    @Override
    public DriveItem updateMetadata(DriveItem item, DriveMetadata metadata) {
        SimpleTelegramClient client = requireClient();
        long chatId = savedMessagesChatId(client);
        TdApi.FormattedText caption = formatted(captionCodec.encode(metadata));
        TdApi.Message updated = await(client.send(new TdApi.EditMessageCaption(chatId, item.messageId(), null, caption, false)));
        return metadata.toDriveItem(updated.id);
    }

    @Override
    public void deleteItems(Collection<DriveItem> items) {
        if (items.isEmpty()) {
            return;
        }
        SimpleTelegramClient client = requireClient();
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

    @Override
    public Path downloadItemFile(DriveItem item) {
        SimpleTelegramClient client = requireClient();
        long chatId = savedMessagesChatId(client);
        TdApi.Message message = await(client.send(new TdApi.GetMessage(chatId, item.messageId())));
        if (!(message.content instanceof TdApi.MessageDocument document)) {
            throw ApiException.notFound("The requested file could not be found in Saved Messages.");
        }

        int fileId = document.document.document.id;
        TdApi.File file = await(client.send(new TdApi.DownloadFile(fileId, 32, 0, 0, false)));

        if (file.local.isDownloadingCompleted && file.local.path != null && !file.local.path.isBlank()) {
            return Path.of(file.local.path);
        }

        // Wait for file download to complete
        for (int attempt = 0; attempt < 120; attempt++) {
            try {
                Thread.sleep(500);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                throw ApiException.serviceUnavailable("Download was interrupted.");
            }
            file = await(client.send(new TdApi.GetFile(fileId)));
            if (file.local.isDownloadingCompleted && file.local.path != null && !file.local.path.isBlank()) {
                return Path.of(file.local.path);
            }
        }

        throw ApiException.serviceUnavailable("File download timed out from Telegram.");
    }

    private DriveItem sendDocument(DriveMetadata metadata, Path file) {
        SimpleTelegramClient client = requireClient();
        long chatId = savedMessagesChatId(client);
        
        TdApi.InputDocument inputDoc = new TdApi.InputDocument();
        inputDoc.document = new TdApi.InputFileLocal(file.toAbsolutePath().toString());
        
        TdApi.InputMessageDocument content = new TdApi.InputMessageDocument();
        content.document = inputDoc;
        content.caption = formatted(captionCodec.encode(metadata));
        
        TdApi.Message message = await(client.sendMessage(new TdApi.SendMessage(chatId, null, null, null, null, content), true));
        return metadata.toDriveItem(message.id);
    }

    private long savedMessagesChatId(SimpleTelegramClient client) {
        TdApi.User me = await(client.getMeAsync());
        return await(client.send(new TdApi.CreatePrivateChat(me.id, false))).id;
    }

    private Optional<DriveItem> itemFromMessage(TdApi.Message message) {
        if (!(message.content instanceof TdApi.MessageDocument document) || document.caption == null) {
            return Optional.empty();
        }
        return captionCodec.decode(document.caption.text).map(metadata -> metadata.toDriveItem(message.id));
    }

    private void ensureMarkerFile() {
        try {
            if (!Files.exists(markerFile)) {
                Files.createDirectories(markerFile.getParent());
                Files.writeString(markerFile, "TeleDrive folder marker");
            }
        } catch (IOException exception) {
            throw ApiException.serviceUnavailable("The folder marker could not be prepared.");
        }
    }

    private TdApi.FormattedText formatted(String text) {
        return new TdApi.FormattedText(text, new TdApi.TextEntity[0]);
    }

    private <T extends TdApi.Object> T await(CompletableFuture<T> future) {
        try {
            return future.get(TELEGRAM_TIMEOUT.toSeconds(), TimeUnit.SECONDS);
        } catch (Exception exception) {
            String message = exception.getMessage() == null ? "" : exception.getMessage();
            log.error("Telegram operation failed: {}", message, exception);
            throw ApiException.serviceUnavailable("Telegram operation failed: " + (message.isEmpty() ? exception.getClass().getSimpleName() : message));
        }
    }
}
