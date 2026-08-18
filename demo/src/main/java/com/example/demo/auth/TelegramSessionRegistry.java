package com.example.demo.auth;

import com.example.demo.config.TelegramDriveProperties;
import com.example.demo.web.ApiException;
import it.tdlight.client.APIToken;
import it.tdlight.client.AuthenticationSupplier;
import it.tdlight.client.SimpleTelegramClient;
import it.tdlight.client.SimpleTelegramClientBuilder;
import it.tdlight.client.SimpleTelegramClientFactory;
import it.tdlight.client.TDLibSettings;
import it.tdlight.jni.TdApi;
import jakarta.annotation.PreDestroy;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import org.springframework.stereotype.Component;

@Component
public class TelegramSessionRegistry {

    private final TelegramDriveProperties properties;
    private final Map<String, TelegramAccountSession> sessions = new ConcurrentHashMap<>();
    private volatile SimpleTelegramClientFactory clientFactory;

    public TelegramSessionRegistry(TelegramDriveProperties properties) {
        this.properties = properties;
    }

    public AuthSnapshot startPhoneLogin(String webSessionId, ApiCredentials credentials, String phoneNumber) {
        String phone = phoneNumber == null ? "" : phoneNumber.replaceAll("[\\s()-]", "");
        if (!phone.matches("\\+?[0-9]{7,16}")) {
            throw ApiException.badRequest("Use an international phone number, for example +15551234567.");
        }
        return start(webSessionId, credentials, AuthenticationSupplier.user(phone));
    }

    public AuthSnapshot startQrLogin(String webSessionId, ApiCredentials credentials) {
        return start(webSessionId, credentials, AuthenticationSupplier.qrCode());
    }

    public AuthSnapshot snapshot(String webSessionId) {
        TelegramAccountSession session = sessions.get(webSessionId);
        return session == null ? new AuthSnapshot(LoginStep.IDLE, null, null, null) : session.snapshot();
    }

    public TelegramAccountSession requireReady(String webSessionId) {
        TelegramAccountSession session = sessions.get(webSessionId);
        if (session == null || !session.isReady()) {
            throw ApiException.unauthorized("Sign in to Telegram before using the drive.");
        }
        return session;
    }

    public void submitCode(String webSessionId, String code) {
        requireSession(webSessionId).submitCode(code);
    }

    public void submitPassword(String webSessionId, String password) {
        requireSession(webSessionId).submitPassword(password);
    }

    public void logout(String webSessionId) {
        TelegramAccountSession session = sessions.remove(webSessionId);
        if (session != null) {
            session.logOut();
        }
    }

    private AuthSnapshot start(String webSessionId, ApiCredentials credentials, AuthenticationSupplier<?> supplier) {
        TelegramAccountSession previous = sessions.remove(webSessionId);
        if (previous != null) {
            previous.close();
        }

        Path sessionDirectory = properties.getSessionRoot().toAbsolutePath().normalize().resolve(sessionDirectoryName(webSessionId));
        TelegramAccountSession accountSession = new TelegramAccountSession(webSessionId, sessionDirectory);
        sessions.put(webSessionId, accountSession);
        try {
            SimpleTelegramClient client = buildClientWithLockRetry(sessionDirectory, credentials, accountSession, supplier);
            accountSession.attach(client);
            return accountSession.snapshot();
        } catch (RuntimeException exception) {
            sessions.remove(webSessionId, accountSession);
            accountSession.onFailure(exception);
            String message = exception.getMessage() == null ? "" : exception.getMessage();
            if (message.contains("Can't lock file") || message.contains("already in use")) {
                throw ApiException.serviceUnavailable("Telegram session is still shutting down. Wait a moment and try again.");
            }
            throw ApiException.serviceUnavailable("Telegram could not start. On Windows, install the Microsoft Visual C++ Redistributable and try again.");
        }
    }

    private SimpleTelegramClient buildClientWithLockRetry(Path sessionDirectory, ApiCredentials credentials,
                                                           TelegramAccountSession accountSession, AuthenticationSupplier<?> supplier) {
        TDLibSettings settings = TDLibSettings.create(new APIToken(credentials.apiId(), credentials.apiHash()));
        settings.setDatabaseDirectoryPath(sessionDirectory.resolve("data"));
        settings.setDownloadedFilesDirectoryPath(sessionDirectory.resolve("downloads"));

        RuntimeException lastError = null;
        for (int attempt = 0; attempt < 5; attempt++) {
            try {
                SimpleTelegramClientBuilder builder = factory().builder(settings);
                builder.setClientInteraction(accountSession);
                builder.addUpdateHandler(TdApi.UpdateAuthorizationState.class, accountSession::onAuthorizationState);
                builder.addDefaultExceptionHandler(accountSession::onFailure);
                builder.addUpdateExceptionHandler(accountSession::onFailure);
                return builder.build(supplier);
            } catch (RuntimeException exception) {
                String message = exception.getMessage() == null ? "" : exception.getMessage();
                if (message.contains("Can't lock file") || message.contains("already in use")) {
                    lastError = exception;
                    try {
                        Thread.sleep(500L * (attempt + 1));
                    } catch (InterruptedException ignored) {
                        Thread.currentThread().interrupt();
                    }
                    continue;
                }
                throw exception;
            }
        }
        throw lastError;
    }

    private TelegramAccountSession requireSession(String webSessionId) {
        TelegramAccountSession session = sessions.get(webSessionId);
        if (session == null) {
            throw ApiException.unauthorized("Start a Telegram sign-in first.");
        }
        return session;
    }

    private synchronized SimpleTelegramClientFactory factory() {
        if (clientFactory == null) {
            clientFactory = new SimpleTelegramClientFactory();
        }
        return clientFactory;
    }

    private String sessionDirectoryName(String webSessionId) {
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256").digest(webSessionId.getBytes(StandardCharsets.UTF_8));
            StringBuilder value = new StringBuilder("web-");
            for (byte b : digest) {
                value.append(String.format("%02x", b));
            }
            return value.toString();
        } catch (NoSuchAlgorithmException exception) {
            throw new IllegalStateException("SHA-256 is unavailable", exception);
        }
    }

    @PreDestroy
    void closeAll() {
        sessions.values().forEach(TelegramAccountSession::close);
        sessions.clear();
        SimpleTelegramClientFactory factory = clientFactory;
        if (factory != null) {
            factory.close();
        }
    }
}
