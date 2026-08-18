package com.example.demo.web;

import com.example.demo.domain.DriveItem;
import com.example.demo.drive.DriveListing;
import com.example.demo.drive.DriveService;
import com.example.demo.drive.StorageSummary;
import org.springframework.core.io.FileSystemResource;
import org.springframework.core.io.Resource;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;

import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;

@RestController
@RequestMapping("/api/drive")
public class DriveController {

    private final DriveService driveService;

    public DriveController(DriveService driveService) {
        this.driveService = driveService;
    }

    @GetMapping("/items")
    public ResponseEntity<DriveListing> items(@RequestParam(value = "parentId", required = false) String parentId) {
        return ResponseEntity.ok(driveService.list(parentId));
    }

    @GetMapping(value = "/sync-stream", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    public SseEmitter syncStream(@RequestParam(value = "parentId", required = false) String parentId) {
        SseEmitter emitter = new SseEmitter(180_000L);
        CompletableFuture.runAsync(() -> {
            try {
                DriveListing listing = driveService.listWithProgress(parentId, (loaded, scanned, total, percent) -> {
                    try {
                        Map<String, Object> progress = Map.of(
                                "type", "PROGRESS",
                                "loaded", loaded,
                                "scanned", scanned,
                                "total", total,
                                "percentage", percent
                        );
                        emitter.send(SseEmitter.event().name("progress").data(progress));
                    } catch (Exception ignored) {
                    }
                });

                Map<String, Object> complete = Map.of(
                        "type", "COMPLETE",
                        "loaded", listing.items().size(),
                        "scanned", listing.items().size(),
                        "total", listing.items().size(),
                        "percentage", 100,
                        "data", listing
                );
                emitter.send(SseEmitter.event().name("complete").data(complete));
                emitter.complete();
            } catch (Exception e) {
                emitter.completeWithError(e);
            }
        });
        return emitter;
    }

    @GetMapping("/folders")
    public ResponseEntity<List<DriveItem>> folders() {
        return ResponseEntity.ok(driveService.allFolders());
    }

    @GetMapping("/search")
    public ResponseEntity<List<DriveItem>> search(@RequestParam("q") String query) {
        return ResponseEntity.ok(driveService.search(query));
    }

    @GetMapping("/storage")
    public ResponseEntity<StorageSummary> storage() {
        return ResponseEntity.ok(driveService.storage());
    }

    @PostMapping("/folders")
    public ResponseEntity<DriveItem> createFolder(@RequestBody CreateFolderRequest request) {
        return ResponseEntity.ok(driveService.createFolder(request.parentId(), request.name()));
    }

    @PostMapping(value = "/upload", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    public ResponseEntity<List<DriveItem>> upload(
            @RequestParam(value = "parentId", required = false) String parentId,
            @RequestParam("files") MultipartFile[] files
    ) {
        return ResponseEntity.ok(driveService.uploadFiles(parentId, files));
    }

    @PostMapping(value = "/upload-folder", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    public ResponseEntity<List<DriveItem>> uploadFolder(
            @RequestParam(value = "parentId", required = false) String parentId,
            @RequestParam("files") MultipartFile[] files
    ) {
        return ResponseEntity.ok(driveService.uploadFolder(parentId, files));
    }

    @GetMapping("/download/{id}")
    public ResponseEntity<Resource> download(@PathVariable("id") String id) {
        DriveItem item = driveService.getItem(id);
        Path filePath = driveService.downloadFile(id);
        Resource resource = new FileSystemResource(filePath);

        String encodedName = URLEncoder.encode(item.name(), StandardCharsets.UTF_8).replace("+", "%20");
        String disposition = "attachment; filename=\"" + item.name() + "\"; filename*=UTF-8''" + encodedName;

        MediaType mediaType;
        try {
            mediaType = MediaType.parseMediaType(item.mimeType());
        } catch (Exception e) {
            mediaType = MediaType.APPLICATION_OCTET_STREAM;
        }

        return ResponseEntity.ok()
                .contentType(mediaType)
                .header(HttpHeaders.CONTENT_DISPOSITION, disposition)
                .body(resource);
    }

    @PatchMapping("/items/{id}")
    public ResponseEntity<DriveItem> rename(@PathVariable("id") String id, @RequestBody RenameRequest request) {
        return ResponseEntity.ok(driveService.rename(id, request.name()));
    }

    @PostMapping("/items/{id}/move")
    public ResponseEntity<DriveItem> move(@PathVariable("id") String id, @RequestBody TargetFolderRequest request) {
        return ResponseEntity.ok(driveService.move(id, request.targetParentId()));
    }

    @PostMapping("/items/{id}/copy")
    public ResponseEntity<DriveItem> copy(@PathVariable("id") String id, @RequestBody TargetFolderRequest request) {
        return ResponseEntity.ok(driveService.copy(id, request.targetParentId()));
    }

    @DeleteMapping("/items/{id}")
    public ResponseEntity<Map<String, Boolean>> delete(@PathVariable("id") String id) {
        driveService.delete(id);
        return ResponseEntity.ok(Map.of("deleted", true));
    }

    public record CreateFolderRequest(String parentId, String name) {}
    public record RenameRequest(String name) {}
    public record TargetFolderRequest(String targetParentId) {}
}
