package com.example.demo.web;

import com.example.demo.domain.DriveItem;
import com.example.demo.drive.DriveListing;
import com.example.demo.drive.DriveService;
import com.example.demo.drive.StorageSummary;
import jakarta.servlet.http.HttpSession;
import java.util.List;
import java.util.Map;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;

@RestController
@RequestMapping("/api/drive")
public class DriveController {

    private final DriveService driveService;

    public DriveController(DriveService driveService) {
        this.driveService = driveService;
    }

    @GetMapping("/items")
    public DriveListing items(@RequestParam(required = false) String parentId, HttpSession session) {
        return driveService.list(session.getId(), parentId);
    }

    @GetMapping("/folders")
    public List<DriveItem> folders(HttpSession session) {
        return driveService.allFolders(session.getId());
    }

    @GetMapping("/search")
    public List<DriveItem> search(@RequestParam String q, HttpSession session) {
        return driveService.search(session.getId(), q);
    }

    @GetMapping("/storage")
    public StorageSummary storage(HttpSession session) {
        return driveService.storage(session.getId());
    }

    @PostMapping("/folders")
    public DriveItem createFolder(@RequestBody CreateFolderRequest request, HttpSession session) {
        return driveService.createFolder(session.getId(), request.parentId(), request.name());
    }

    @PostMapping(value = "/upload", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    public List<DriveItem> upload(
            @RequestParam(required = false) String parentId,
            @RequestParam("files") MultipartFile[] files,
            HttpSession session
    ) {
        return driveService.uploadFiles(session.getId(), parentId, files);
    }

    @PostMapping(value = "/upload-folder", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    public List<DriveItem> uploadFolder(
            @RequestParam(required = false) String parentId,
            @RequestParam("files") MultipartFile[] files,
            HttpSession session
    ) {
        return driveService.uploadFolder(session.getId(), parentId, files);
    }

    @PatchMapping("/items/{id}")
    public DriveItem rename(@PathVariable String id, @RequestBody RenameRequest request, HttpSession session) {
        return driveService.rename(session.getId(), id, request.name());
    }

    @PostMapping("/items/{id}/move")
    public DriveItem move(@PathVariable String id, @RequestBody TargetFolderRequest request, HttpSession session) {
        return driveService.move(session.getId(), id, request.targetParentId());
    }

    @PostMapping("/items/{id}/copy")
    public DriveItem copy(@PathVariable String id, @RequestBody TargetFolderRequest request, HttpSession session) {
        return driveService.copy(session.getId(), id, request.targetParentId());
    }

    @DeleteMapping("/items/{id}")
    public Map<String, Boolean> delete(@PathVariable String id, HttpSession session) {
        driveService.delete(session.getId(), id);
        return Map.of("deleted", true);
    }

    public record CreateFolderRequest(String parentId, String name) {
    }

    public record RenameRequest(String name) {
    }

    public record TargetFolderRequest(String targetParentId) {
    }
}
