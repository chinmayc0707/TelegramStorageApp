package com.example.demo.domain;

import java.nio.charset.StandardCharsets;
import java.util.Base64;
import java.util.Optional;
import org.springframework.stereotype.Component;
import tools.jackson.databind.ObjectMapper;

@Component
public class CaptionCodec {

    private static final String PREFIX = "#tgdrive:v1:";
    private final ObjectMapper objectMapper;

    public CaptionCodec(ObjectMapper objectMapper) {
        this.objectMapper = objectMapper;
    }

    public String encode(DriveMetadata metadata) {
        try {
            byte[] json = objectMapper.writeValueAsBytes(metadata);
            return PREFIX + Base64.getUrlEncoder().withoutPadding().encodeToString(json);
        } catch (Exception exception) {
            throw new IllegalStateException("Unable to encode drive metadata", exception);
        }
    }

    public Optional<DriveMetadata> decode(String caption) {
        if (caption == null || !caption.startsWith(PREFIX)) {
            return Optional.empty();
        }
        try {
            String payload = caption.substring(PREFIX.length());
            byte[] json = Base64.getUrlDecoder().decode(payload.getBytes(StandardCharsets.US_ASCII));
            DriveMetadata metadata = objectMapper.readValue(json, DriveMetadata.class);
            return metadata.version() == DriveMetadata.CURRENT_VERSION ? Optional.of(metadata) : Optional.empty();
        } catch (Exception ignored) {
            return Optional.empty();
        }
    }
}
