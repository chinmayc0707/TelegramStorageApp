package com.example.demo.drive;

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
import java.util.*;
import org.springframework.stereotype.Service;
import org.springframework.web.multipart.MultipartFile;

@Service
public class DriveService {

    private final SavedMessagesGateway gateway;
    private final Path stagingRoot;

    public DriveService(SavedMessagesGateway gateway, TelegramDriveProperties properties) {
        this.gateway = gateway;
        this.stagingRoot = properties.getUploadRoot().toAbsolutePath().normalize();
    }

    public synchronized DriveListing list(String requestedParentId) {
        return listWithProgress(requestedParentId, null);
    }

    public synchronized DriveListing listWithProgress(String requestedParentId, SavedMessagesGateway.ProgressListener listener) {
        List<DriveItem> allItems = gateway.listItems(listener != null ? listener : (loaded, scanned, total, pct) -> {});
        String parentId = normalizeParentId(requestedParentId);
        Map<String, DriveItem> byId = byId(allItems);
        verifyFolder(parentId, byId);
        List<DriveItem> children = allItems.stream()
                .filter(item -> item.parentId().equals(parentId))
                .sorted(Comparator.comparing(DriveItem::isFolder).reversed().thenComparing(item -> item.name().toLowerCase()))
                .toList();
        return new DriveListing(children, breadcrumbs(parentId, byId));
    }

    public synchronized List<DriveItem> allFolders() {
        return gateway.listItems().stream()
                .filter(DriveItem::isFolder)
                .sorted(Comparator.comparing(DriveItem::name, String.CASE_INSENSITIVE_ORDER))
                .toList();
    }

    public synchronized List<DriveItem> search(String query) {
        String term = query == null ? "" : query.trim().toLowerCase();
        if (term.isBlank()) {
            return List.of();
        }
        return gateway.listItems().stream()
                .filter(item -> item.name().toLowerCase().contains(term))
                .sorted(Comparator.comparing(DriveItem::isFolder).reversed().thenComparing(item -> item.name().toLowerCase()))
                .toList();
    }

    public synchronized StorageSummary storage() {
        List<DriveItem> allItems = gateway.listItems();
        long usedBytes = allItems.stream().filter(item -> item.type() == DriveItemType.FILE).mapToLong(DriveItem::size).sum();
        long fileCount = allItems.stream().filter(item -> item.type() == DriveItemType.FILE).count();
        long folderCount = allItems.size() - fileCount;
        return new StorageSummary(usedBytes, fileCount, folderCount);
    }

    public synchronized DriveItem createFolder(String rawParentId, String rawName) {
        String parentId = normalizeParentId(rawParentId);
        String name = DriveNamePolicy.requireSafeName(rawName);
        Map<String, DriveItem> byId = byId(gateway.listItems());
        verifyFolder(parentId, byId);
        DriveMetadata metadata = metadata(parentId, DriveItemType.FOLDER, name, 0, "application/x-tgdrive-folder");
        return gateway.createFolder(metadata);
    }

    public synchronized List<DriveItem> uploadFiles(String rawParentId, MultipartFile[] files) {
        String parentId = normalizeParentId(rawParentId);
        if (files == null || files.length == 0) {
            throw ApiException.badRequest("Choose at least one file to upload.");
        }
        Map<String, DriveItem> byId = byId(gateway.listItems());
        verifyFolder(parentId, byId);
        List<DriveItem> uploaded = new ArrayList<>();
        for (MultipartFile file : files) {
            if (file == null || file.isEmpty()) {
                continue;
            }
            String name = DriveNamePolicy.requireSafeName(fileNameOnly(file.getOriginalFilename()));
            uploaded.add(uploadOne(parentId, name, file));
        }
        if (uploaded.isEmpty()) {
            throw ApiException.badRequest("Choose a non-empty file to upload.");
        }
        return uploaded;
    }

    public synchronized List<DriveItem> uploadFolder(String rawParentId, MultipartFile[] files) {
        String rootParentId = normalizeParentId(rawParentId);
        if (files == null || files.length == 0) {
            throw ApiException.badRequest("Choose a folder that contains at least one file.");
        }
        Map<String, DriveItem> existing = byId(gateway.listItems());
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
            String parentId = ensureFolders(folderIds, segments, created);
            created.add(uploadOne(parentId, fileName, file));
        }
        if (created.isEmpty()) {
            throw ApiException.badRequest("The selected folder did not contain uploadable files.");
        }
        return created;
    }

    public synchronized Path downloadFile(String itemId) {
        DriveItem item = requireItem(itemId, byId(gateway.listItems()));
        if (item.isFolder()) {
            throw ApiException.badRequest("Folders cannot be downloaded directly.");
        }
        return gateway.downloadItemFile(item);
    }

    public synchronized DriveItem getItem(String itemId) {
        return requireItem(itemId, byId(gateway.listItems()));
    }

    public synchronized DriveItem rename(String itemId, String rawName) {
        String name = DriveNamePolicy.requireSafeName(rawName);
        DriveItem item = requireItem(itemId, byId(gateway.listItems()));
        DriveMetadata metadata = metadata(item.parentId(), item.type(), name, item.size(), item.mimeType());
        metadata = new DriveMetadata(metadata.version(), item.id(), metadata.parentId(), metadata.type(), metadata.name(), metadata.size(), metadata.mimeType(), metadata.modifiedAt());
        return gateway.updateMetadata(item, metadata);
    }

    public synchronized DriveItem move(String itemId, String rawTargetParentId) {
        String targetParentId = normalizeParentId(rawTargetParentId);
        Map<String, DriveItem> byId = byId(gateway.listItems());
        DriveItem item = requireItem(itemId, byId);
        verifyFolder(targetParentId, byId);
        if (item.id().equals(targetParentId)) {
            throw ApiException.badRequest("A folder cannot be moved into itself.");
        }
        if (item.isFolder() && isDescendant(targetParentId, item.id(), byId)) {
            throw ApiException.badRequest("A folder cannot be moved into one of its descendants.");
        }
        DriveMetadata metadata = preserveIdentity(item, targetParentId);
        return gateway.updateMetadata(item, metadata);
    }

    public synchronized DriveItem copy(String itemId, String rawTargetParentId) {
        String targetParentId = normalizeParentId(rawTargetParentId);
        Map<String, DriveItem> byId = byId(gateway.listItems());
        DriveItem source = requireItem(itemId, byId);
        verifyFolder(targetParentId, byId);
        return copyRecursively(source, targetParentId, byId, new HashSet<>());
    }

    public synchronized void delete(String itemId) {
        Map<String, DriveItem> byId = byId(gateway.listItems());
        DriveItem item = requireItem(itemId, byId);
        gateway.deleteItems(descendantsInclusive(item, byId));
    }

    private DriveItem copyRecursively(DriveItem source, String targetParentId, Map<String, DriveItem> byId, Set<String> copiedIds) {
        if (!copiedIds.add(source.id())) {
            throw ApiException.badRequest("The drive contains a circular folder reference that cannot be copied.");
        }
        DriveMetadata metadata = metadata(targetParentId, source.type(), source.name(), source.size(), source.mimeType());
        DriveItem copied = gateway.copyItem(source, metadata);
        if (source.isFolder()) {
            for (DriveItem child : byId.values().stream()
                    .filter(item -> item.parentId().equals(source.id()))
                    .sorted(Comparator.comparing(DriveItem::name, String.CASE_INSENSITIVE_ORDER))
                    .toList()) {
                copyRecursively(child, copied.id(), byId, copiedIds);
            }
        }
        return copied;
    }

    private DriveItem uploadOne(String parentId, String name, MultipartFile file) {
        Path stagedFile = null;
        try {
            Files.createDirectories(stagingRoot);
            stagedFile = stagingRoot.resolve(UUID.randomUUID() + "-" + name).normalize();
            if (!stagedFile.startsWith(stagingRoot)) {
                throw ApiException.badRequest("Invalid upload filename.");
            }
            file.transferTo(stagedFile);
            String mimeType = file.getContentType() == null ? "application/octet-stream" : file.getContentType();
            return gateway.uploadFile(metadata(parentId, DriveItemType.FILE, name, file.getSize(), mimeType), stagedFile);
        } catch (IOException exception) {
            throw ApiException.serviceUnavailable("The upload could not be staged for Telegram.");
        } finally {
            if (stagedFile != null) {
                try {
                    Files.deleteIfExists(stagedFile);
                } catch (IOException ignored) {
                }
            }
        }
    }

    private String ensureFolders(Map<String, String> folderIds, List<String> segments, List<DriveItem> created) {
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
                DriveItem folder = gateway.createFolder(metadata(parentId, DriveItemType.FOLDER, segment, 0, "application/x-tgdrive-folder"));
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
        Set<String> visited = new HashSet<>();
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
        collect(root, byId, result, new HashSet<>());
        return result;
    }

    private void collect(DriveItem current, Map<String, DriveItem> byId, List<DriveItem> result, Set<String> visited) {
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
        Set<String> visited = new HashSet<>();
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
}
