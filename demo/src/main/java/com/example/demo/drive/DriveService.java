package com.example.demo.drive;

import com.example.demo.auth.TelegramAccountSession;
import com.example.demo.auth.TelegramSessionRegistry;
import com.example.demo.config.TelegramDriveProperties;
import com.example.demo.domain.DriveItem;
import com.example.demo.domain.DriveItemType;
import com.example.demo.domain.DriveMetadata;
import com.example.demo.domain.DriveNamePolicy;
import com.example.demo.telegram.SavedMessagesGateway;
import com.example.demo.web.ApiException;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.springframework.stereotype.Service;
import org.springframework.web.multipart.MultipartFile;

@Service
public class DriveService {

    private final TelegramSessionRegistry sessions;
    private final SavedMessagesGateway gateway;
    private final Path stagingRoot;

    public DriveService(TelegramSessionRegistry sessions, SavedMessagesGateway gateway, TelegramDriveProperties properties) {
        this.sessions = sessions;
        this.gateway = gateway;
        this.stagingRoot = properties.getUploadRoot().toAbsolutePath().normalize();
    }

    public DriveListing list(String webSessionId, String requestedParentId) {
        TelegramAccountSession session = sessions.requireReady(webSessionId);
        synchronized (session) {
            List<DriveItem> allItems = gateway.listItems(session);
            String parentId = normalizeParentId(requestedParentId);
            Map<String, DriveItem> byId = byId(allItems);
            verifyFolder(parentId, byId);
            List<DriveItem> children = allItems.stream()
                    .filter(item -> item.parentId().equals(parentId))
                    .sorted(Comparator.comparing(DriveItem::isFolder).reversed().thenComparing(item -> item.name().toLowerCase()))
                    .toList();
            return new DriveListing(children, breadcrumbs(parentId, byId));
        }
    }

    public List<DriveItem> allFolders(String webSessionId) {
        TelegramAccountSession session = sessions.requireReady(webSessionId);
        synchronized (session) {
            return gateway.listItems(session).stream()
                    .filter(DriveItem::isFolder)
                    .sorted(Comparator.comparing(DriveItem::name, String.CASE_INSENSITIVE_ORDER))
                    .toList();
        }
    }

    public List<DriveItem> search(String webSessionId, String query) {
        TelegramAccountSession session = sessions.requireReady(webSessionId);
        String term = query == null ? "" : query.trim().toLowerCase();
        if (term.isBlank()) {
            return List.of();
        }
        synchronized (session) {
            return gateway.listItems(session).stream()
                    .filter(item -> item.name().toLowerCase().contains(term))
                    .sorted(Comparator.comparing(DriveItem::isFolder).reversed().thenComparing(item -> item.name().toLowerCase()))
                    .toList();
        }
    }

    public StorageSummary storage(String webSessionId) {
        TelegramAccountSession session = sessions.requireReady(webSessionId);
        synchronized (session) {
            List<DriveItem> allItems = gateway.listItems(session);
            long usedBytes = allItems.stream().filter(item -> item.type() == DriveItemType.FILE).mapToLong(DriveItem::size).sum();
            long fileCount = allItems.stream().filter(item -> item.type() == DriveItemType.FILE).count();
            long folderCount = allItems.size() - fileCount;
            return new StorageSummary(usedBytes, fileCount, folderCount);
        }
    }

    public DriveItem createFolder(String webSessionId, String rawParentId, String rawName) {
        TelegramAccountSession session = sessions.requireReady(webSessionId);
        String parentId = normalizeParentId(rawParentId);
        String name = DriveNamePolicy.requireSafeName(rawName);
        synchronized (session) {
            Map<String, DriveItem> byId = byId(gateway.listItems(session));
            verifyFolder(parentId, byId);
            DriveMetadata metadata = metadata(parentId, DriveItemType.FOLDER, name, 0, "application/x-tgdrive-folder");
            return gateway.createFolder(session, metadata);
        }
    }

    public List<DriveItem> uploadFiles(String webSessionId, String rawParentId, MultipartFile[] files) {
        TelegramAccountSession session = sessions.requireReady(webSessionId);
        String parentId = normalizeParentId(rawParentId);
        if (files == null || files.length == 0) {
            throw ApiException.badRequest("Choose at least one file to upload.");
        }
        synchronized (session) {
            Map<String, DriveItem> byId = byId(gateway.listItems(session));
            verifyFolder(parentId, byId);
            List<DriveItem> uploaded = new ArrayList<>();
            for (MultipartFile file : files) {
                if (file == null || file.isEmpty()) {
                    continue;
                }
                String name = DriveNamePolicy.requireSafeName(fileNameOnly(file.getOriginalFilename()));
                uploaded.add(uploadOne(session, parentId, name, file));
            }
            if (uploaded.isEmpty()) {
                throw ApiException.badRequest("Choose a non-empty file to upload.");
            }
            return uploaded;
        }
    }

    public List<DriveItem> uploadFolder(String webSessionId, String rawParentId, MultipartFile[] files) {
        TelegramAccountSession session = sessions.requireReady(webSessionId);
        String rootParentId = normalizeParentId(rawParentId);
        if (files == null || files.length == 0) {
            throw ApiException.badRequest("Choose a folder that contains at least one file.");
        }
        synchronized (session) {
            Map<String, DriveItem> existing = byId(gateway.listItems(session));
            verifyFolder(rootParentId, existing);
            Map<String, String> folderIds = new LinkedHashMap<>();
            folderIds.put("", rootParentId);
            List<DriveItem> created = new ArrayList<>();
            for (MultipartFile file : files) {
                if (file == null || file.isEmpty()) {
                    continue;
                }
                List<String> segments = safeRelativePath(file.getOriginalFilename());
                if (segments.isEmpty()) {
                    continue;
                }
                String fileName = segments.remove(segments.size() - 1);
                String parentId = ensureFolders(session, folderIds, segments, created);
                created.add(uploadOne(session, parentId, fileName, file));
            }
            if (created.isEmpty()) {
                throw ApiException.badRequest("The selected folder did not contain uploadable files.");
            }
            return created;
        }
    }

    public DriveItem rename(String webSessionId, String itemId, String rawName) {
        TelegramAccountSession session = sessions.requireReady(webSessionId);
        String name = DriveNamePolicy.requireSafeName(rawName);
        synchronized (session) {
            DriveItem item = requireItem(itemId, byId(gateway.listItems(session)));
            DriveMetadata metadata = metadata(item.parentId(), item.type(), name, item.size(), item.mimeType());
            metadata = new DriveMetadata(metadata.version(), item.id(), metadata.parentId(), metadata.type(), metadata.name(), metadata.size(), metadata.mimeType(), metadata.modifiedAt());
            return gateway.updateMetadata(session, item, metadata);
        }
    }

    public DriveItem move(String webSessionId, String itemId, String rawTargetParentId) {
        TelegramAccountSession session = sessions.requireReady(webSessionId);
        String targetParentId = normalizeParentId(rawTargetParentId);
        synchronized (session) {
            Map<String, DriveItem> byId = byId(gateway.listItems(session));
            DriveItem item = requireItem(itemId, byId);
            verifyFolder(targetParentId, byId);
            if (item.id().equals(targetParentId)) {
                throw ApiException.badRequest("A folder cannot be moved into itself.");
            }
            if (item.isFolder() && isDescendant(targetParentId, item.id(), byId)) {
                throw ApiException.badRequest("A folder cannot be moved into one of its descendants.");
            }
            DriveMetadata metadata = preserveIdentity(item, targetParentId);
            return gateway.updateMetadata(session, item, metadata);
        }
    }

    public DriveItem copy(String webSessionId, String itemId, String rawTargetParentId) {
        TelegramAccountSession session = sessions.requireReady(webSessionId);
        String targetParentId = normalizeParentId(rawTargetParentId);
        synchronized (session) {
            Map<String, DriveItem> byId = byId(gateway.listItems(session));
            DriveItem source = requireItem(itemId, byId);
            verifyFolder(targetParentId, byId);
            return copyRecursively(session, source, targetParentId, byId, new java.util.HashSet<>());
        }
    }

    public void delete(String webSessionId, String itemId) {
        TelegramAccountSession session = sessions.requireReady(webSessionId);
        synchronized (session) {
            Map<String, DriveItem> byId = byId(gateway.listItems(session));
            DriveItem item = requireItem(itemId, byId);
            gateway.deleteItems(session, descendantsInclusive(item, byId));
        }
    }

    private DriveItem copyRecursively(TelegramAccountSession session, DriveItem source, String targetParentId, Map<String, DriveItem> byId, java.util.Set<String> copiedIds) {
        if (!copiedIds.add(source.id())) {
            throw ApiException.badRequest("The drive contains a circular folder reference that cannot be copied.");
        }
        DriveMetadata metadata = metadata(targetParentId, source.type(), source.name(), source.size(), source.mimeType());
        DriveItem copied = gateway.copyItem(session, source, metadata);
        if (source.isFolder()) {
            for (DriveItem child : byId.values().stream()
                    .filter(item -> item.parentId().equals(source.id()))
                    .sorted(Comparator.comparing(DriveItem::name, String.CASE_INSENSITIVE_ORDER))
                    .toList()) {
                copyRecursively(session, child, copied.id(), byId, copiedIds);
            }
        }
        return copied;
    }

    private DriveItem uploadOne(TelegramAccountSession session, String parentId, String name, MultipartFile file) {
        Path stagedFile = null;
        try {
            Files.createDirectories(stagingRoot.resolve(sessionDirectoryName(session.sessionKey())));
            stagedFile = stagingRoot.resolve(sessionDirectoryName(session.sessionKey()))
                    .resolve(UUID.randomUUID() + "-" + name).normalize();
            if (!stagedFile.startsWith(stagingRoot)) {
                throw ApiException.badRequest("Invalid upload filename.");
            }
            file.transferTo(stagedFile);
            String mimeType = file.getContentType() == null ? "application/octet-stream" : file.getContentType();
            return gateway.uploadFile(session, metadata(parentId, DriveItemType.FILE, name, file.getSize(), mimeType), stagedFile);
        } catch (IOException exception) {
            throw ApiException.serviceUnavailable("The upload could not be staged for Telegram.");
        } finally {
            if (stagedFile != null) {
                try {
                    Files.deleteIfExists(stagedFile);
                } catch (IOException ignored) {
                    // TDLib may still be releasing the file. Its staging directory is isolated per browser session.
                }
            }
        }
    }

    private String ensureFolders(TelegramAccountSession session, Map<String, String> folderIds, List<String> segments, List<DriveItem> created) {
        String parentId = folderIds.get("");
        StringBuilder currentPath = new StringBuilder();
        for (String segment : segments) {
            if (currentPath.length() > 0) {
                currentPath.append('/');
            }
            currentPath.append(segment);
            String path = currentPath.toString();
            String existingId = folderIds.get(path);
            if (existingId == null) {
                DriveItem folder = gateway.createFolder(session, metadata(parentId, DriveItemType.FOLDER, segment, 0, "application/x-tgdrive-folder"));
                created.add(folder);
                folderIds.put(path, folder.id());
                existingId = folder.id();
            }
            parentId = existingId;
        }
        return parentId;
    }

    private List<DriveItem> breadcrumbs(String parentId, Map<String, DriveItem> byId) {
        List<DriveItem> result = new ArrayList<>();
        String cursor = parentId;
        java.util.Set<String> visited = new java.util.HashSet<>();
        while (!DriveMetadata.ROOT_ID.equals(cursor) && visited.add(cursor)) {
            DriveItem item = byId.get(cursor);
            if (item == null) {
                break;
            }
            result.add(0, item);
            cursor = item.parentId();
        }
        return result;
    }

    private Collection<DriveItem> descendantsInclusive(DriveItem root, Map<String, DriveItem> byId) {
        List<DriveItem> result = new ArrayList<>();
        collect(root, byId, result, new java.util.HashSet<>());
        return result;
    }

    private void collect(DriveItem current, Map<String, DriveItem> byId, List<DriveItem> result, java.util.Set<String> visited) {
        if (!visited.add(current.id())) {
            return;
        }
        for (DriveItem child : byId.values()) {
            if (child.parentId().equals(current.id())) {
                collect(child, byId, result, visited);
            }
        }
        result.add(current);
    }

    private boolean isDescendant(String candidateId, String ancestorId, Map<String, DriveItem> byId) {
        String cursor = candidateId;
        java.util.Set<String> visited = new java.util.HashSet<>();
        while (!DriveMetadata.ROOT_ID.equals(cursor) && visited.add(cursor)) {
            if (ancestorId.equals(cursor)) {
                return true;
            }
            DriveItem item = byId.get(cursor);
            if (item == null) {
                return false;
            }
            cursor = item.parentId();
        }
        return false;
    }

    private DriveMetadata preserveIdentity(DriveItem item, String parentId) {
        return new DriveMetadata(
                DriveMetadata.CURRENT_VERSION,
                item.id(),
                parentId,
                item.type(),
                item.name(),
                item.size(),
                item.mimeType(),
                Instant.now().toEpochMilli()
        );
    }

    private DriveMetadata metadata(String parentId, DriveItemType type, String name, long size, String mimeType) {
        return new DriveMetadata(
                DriveMetadata.CURRENT_VERSION,
                UUID.randomUUID().toString(),
                parentId,
                type,
                name,
                size,
                mimeType,
                Instant.now().toEpochMilli()
        );
    }

    private Map<String, DriveItem> byId(List<DriveItem> items) {
        Map<String, DriveItem> map = new HashMap<>();
        items.forEach(item -> map.put(item.id(), item));
        return map;
    }

    private void verifyFolder(String id, Map<String, DriveItem> byId) {
        if (DriveMetadata.ROOT_ID.equals(id)) {
            return;
        }
        DriveItem item = byId.get(id);
        if (item == null || !item.isFolder()) {
            throw ApiException.notFound("The destination folder no longer exists.");
        }
    }

    private DriveItem requireItem(String itemId, Map<String, DriveItem> byId) {
        DriveItem item = byId.get(itemId);
        if (item == null) {
            throw ApiException.notFound("That item no longer exists.");
        }
        return item;
    }

    private String normalizeParentId(String parentId) {
        return parentId == null || parentId.isBlank() ? DriveMetadata.ROOT_ID : parentId;
    }

    private String fileNameOnly(String name) {
        if (name == null) {
            return "";
        }
        String normalized = name.replace('\\', '/');
        int lastSeparator = normalized.lastIndexOf('/');
        return lastSeparator >= 0 ? normalized.substring(lastSeparator + 1) : normalized;
    }

    private List<String> safeRelativePath(String originalName) {
        String normalized = originalName == null ? "" : originalName.replace('\\', '/');
        List<String> segments = new ArrayList<>();
        for (String segment : normalized.split("/")) {
            if (segment.isBlank()) {
                continue;
            }
            if (".".equals(segment) || "..".equals(segment)) {
                throw ApiException.badRequest("Folder uploads cannot contain relative path segments.");
            }
            segments.add(DriveNamePolicy.requireSafeName(segment));
        }
        return segments;
    }

    private String sessionDirectoryName(String sessionKey) {
        return Integer.toUnsignedString(sessionKey.hashCode(), 36);
    }
}
