package com.example.demo.drive;

import com.example.demo.domain.DriveItem;
import java.util.List;

public record DriveListing(List<DriveItem> items, List<DriveItem> breadcrumbs) {
}
