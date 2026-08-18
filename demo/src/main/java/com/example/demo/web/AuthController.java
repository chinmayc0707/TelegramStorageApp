package com.example.demo.web;

import com.example.demo.auth.ApiCredentials;
import com.example.demo.auth.AuthSnapshot;
import com.example.demo.auth.QrCodeImageService;
import com.example.demo.auth.TelegramSessionRegistry;
import jakarta.servlet.http.HttpSession;
import java.util.Map;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/auth")
public class AuthController {

    private final TelegramSessionRegistry sessions;
    private final QrCodeImageService qrCodes;

    public AuthController(TelegramSessionRegistry sessions, QrCodeImageService qrCodes) {
        this.sessions = sessions;
        this.qrCodes = qrCodes;
    }

    @PostMapping("/phone")
    public AuthSnapshot startPhoneLogin(@RequestBody PhoneLoginRequest request, HttpSession session) {
        return sessions.startPhoneLogin(session.getId(), new ApiCredentials(request.apiId(), request.apiHash()), request.phone());
    }

    @PostMapping("/qr")
    public AuthSnapshot startQrLogin(@RequestBody CredentialsRequest request, HttpSession session) {
        return sessions.startQrLogin(session.getId(), new ApiCredentials(request.apiId(), request.apiHash()));
    }

    @GetMapping("/state")
    public AuthSnapshot state(HttpSession session) {
        return sessions.snapshot(session.getId());
    }

    @GetMapping("/qr-image")
    public Map<String, String> qrImage(HttpSession session) {
        AuthSnapshot snapshot = sessions.snapshot(session.getId());
        if (snapshot.qrLink() == null || snapshot.qrLink().isBlank()) {
            throw ApiException.conflict("Telegram has not issued a QR code yet.");
        }
        return Map.of("image", qrCodes.toDataUrl(snapshot.qrLink()));
    }

    @PostMapping("/code")
    public AuthSnapshot submitCode(@RequestBody ValueRequest request, HttpSession session) {
        sessions.submitCode(session.getId(), request.value());
        return sessions.snapshot(session.getId());
    }

    @PostMapping("/password")
    public AuthSnapshot submitPassword(@RequestBody ValueRequest request, HttpSession session) {
        sessions.submitPassword(session.getId(), request.value());
        return sessions.snapshot(session.getId());
    }

    @PostMapping("/logout")
    public Map<String, Boolean> logout(HttpSession session) {
        sessions.logout(session.getId());
        session.invalidate();
        return Map.of("loggedOut", true);
    }

    public record CredentialsRequest(int apiId, String apiHash) {
    }

    public record PhoneLoginRequest(int apiId, String apiHash, String phone) {
    }

    public record ValueRequest(String value) {
    }
}
