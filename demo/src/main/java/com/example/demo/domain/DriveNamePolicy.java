package com.example.demo.domain;

import com.example.demo.web.ApiException;
import java.util.regex.Pattern;

public final class DriveNamePolicy {

    private static final Pattern CONTROL_CHARACTERS = Pattern.compile("[\\p{Cntrl}]");

    private DriveNamePolicy() {
    }

    public static String requireSafeName(String rawName) {
        String name = rawName == null ? "" : rawName.trim();
        if (name.isBlank() || ".".equals(name) || "..".equals(name)) {
            throw ApiException.badRequest("Enter a name for the item.");
        }
        if (name.length() > 120 || name.contains("/") || name.contains("\\\\") || CONTROL_CHARACTERS.matcher(name).find()) {
            throw ApiException.badRequest("Names must be at most 120 characters and cannot contain path separators.");
        }
        return name;
    }
}
