package com.example.demo;

import it.tdlight.client.*;
import it.tdlight.jni.TdApi;
import jakarta.annotation.PostConstruct;
import jakarta.annotation.PreDestroy;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.Comparator;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

@RestController
@RequestMapping("/login")
public class Login implements ClientInteraction {

    private static final Logger log = LoggerFactory.getLogger(Login.class);

    @Value("${telegram.database:./telegram-data}")
    private String dbPath;

    @Value("${telegram.apiId}")
    private int apiId;

    @Value("${telegram.apiHash}")
    private String apiHash;

    private SimpleTelegramClient client;
    private SimpleTelegramClientFactory clientFactory;

    private final AtomicReference<CompletableFuture<String>> codeFuture = new AtomicReference<>();
    private final AtomicReference<CompletableFuture<String>> passwordFuture = new AtomicReference<>();

    private volatile String currentPhoneNumber = "";
    private volatile String currentStatus = "IDLE"; // IDLE, WAITING_FOR_CODE, WAITING_FOR_PASSWORD, READY, ERROR, LOGGED_OUT
    private volatile String codeType = "";
    private volatile String codeDescription = "";
    private volatile int codeTimeout = 60;
    private volatile String accountName = "";
    private volatile String qrCodeLink = "";
    private volatile String lastError = null;
    /** Subdirectory of dbPath used as TDLib database for the currently active user. */
    private volatile String currentDataSubDir = "data";

    /** Returns the per-user TDLib database subdirectory name derived from the phone number. */
    private String dataSubDir(String phone) {
        if (phone == null || phone.isBlank()) return "data";
        // Strip everything except digits so the path is always filesystem-safe
        String digits = phone.replaceAll("[^0-9]", "");
        return digits.isEmpty() ? "data" : "data-" + digits;
    }

    @PostConstruct
    public void autoStart() {
        Path databasePath = Paths.get(dbPath).toAbsolutePath().normalize();
        // Load the last used phone to determine which user's DB directory to resume
        String savedPhone = loadSavedPhone();
        String dataDir = dataSubDir(savedPhone);

        // Check the per-user directory; fall back to legacy "data" for backward compat
        boolean hasSession = Files.exists(databasePath.resolve(dataDir));
        if (!hasSession && Files.exists(databasePath.resolve("data"))) {
            dataDir = "data";
            hasSession = true;
        }
        if (!hasSession) {
            log.info("No existing Telegram session found – waiting for manual login.");
            return;
        }
        log.info("Existing Telegram session detected ({}) – resuming automatically.", dataDir);
        this.currentDataSubDir = dataDir;
        this.currentStatus = "STARTING";
        if (savedPhone != null) this.currentPhoneNumber = savedPhone;
        final String resolvedDir = dataDir;
        final String resolvedPhone = savedPhone;
        CompletableFuture.runAsync(() -> {
            try {
                startClientWithPhone(resolvedPhone, resolvedDir);
            } catch (Exception e) {
                log.warn("Auto-resume failed (will require manual login): {}", e.getMessage());
                currentStatus = "ERROR";
                lastError = "Auto-resume failed: " + e.getMessage();
            }
        });
    }

    @PreDestroy
    public void shutdown() {
        log.info("Application shutting down – closing Telegram client.");
        closeExistingClient();
        if (clientFactory != null) {
            try {
                clientFactory.close();
            } catch (Exception ignored) {
            }
        }
    }

    @GetMapping("/setPhno")
    public ResponseEntity<AuthResponse> setPhoneNumberGet(@RequestParam("phoneNumber") String phoneNumber) {
        return initiateLoginFlow(phoneNumber);
    }

    @PostMapping("/setPhno")
    public ResponseEntity<AuthResponse> setPhoneNumberPost(@RequestBody(required = false) Map<String, String> body,
                                                          @RequestParam(value = "phoneNumber", required = false) String paramPhone) {
        String phone = body != null && body.containsKey("phoneNumber") ? body.get("phoneNumber") : paramPhone;
        return initiateLoginFlow(phone);
    }

    @GetMapping("/qr")
    public ResponseEntity<AuthResponse> qrLoginGet() {
        return initiateQrLoginFlow();
    }

    @PostMapping("/qr")
    public ResponseEntity<AuthResponse> qrLoginPost() {
        return initiateQrLoginFlow();
    }

    @GetMapping("/status")
    public ResponseEntity<AuthResponse> getStatus() {
        return ResponseEntity.ok(getSnapshot());
    }

    @GetMapping("/submitOtp")
    public ResponseEntity<AuthResponse> submitOtpGet(@RequestParam("code") String code) {
        return handleOtpSubmission(code);
    }

    @PostMapping("/submitOtp")
    public ResponseEntity<AuthResponse> submitOtpPost(@RequestBody(required = false) Map<String, String> body,
                                                     @RequestParam(value = "code", required = false) String paramCode) {
        String code = body != null && body.containsKey("code") ? body.get("code") : paramCode;
        return handleOtpSubmission(code);
    }

    @GetMapping("/submitPassword")
    public ResponseEntity<AuthResponse> submitPasswordGet(@RequestParam("password") String password) {
        return handlePasswordSubmission(password);
    }

    @PostMapping("/submitPassword")
    public ResponseEntity<AuthResponse> submitPasswordPost(@RequestBody(required = false) Map<String, String> body,
                                                          @RequestParam(value = "password", required = false) String paramPassword) {
        String password = body != null && body.containsKey("password") ? body.get("password") : paramPassword;
        return handlePasswordSubmission(password);
    }

    @GetMapping("/resendOtp")
    public ResponseEntity<AuthResponse> resendOtpGet() {
        return handleResendOtp();
    }

    @PostMapping("/resendOtp")
    public ResponseEntity<AuthResponse> resendOtpPost() {
        return handleResendOtp();
    }

    @GetMapping("/logout")
    public ResponseEntity<AuthResponse> logoutGet() {
        return handleLogout();
    }

    @PostMapping("/logout")
    public ResponseEntity<AuthResponse> logoutPost() {
        return handleLogout();
    }


    private synchronized ResponseEntity<AuthResponse> initiateQrLoginFlow() {
        this.currentPhoneNumber = "";
        this.lastError = null;
        this.currentStatus = "STARTING";
        this.codeType = "qr";
        this.codeDescription = "Open Telegram on your mobile phone, go to Settings -> Devices -> Add Device and scan the QR code to log in.";
        this.accountName = "";
        this.qrCodeLink = "";

        log.info("Initiating Telegram QR Code login flow");

        try {
            closeExistingClient();

            Path databasePath = Paths.get(dbPath).toAbsolutePath().normalize();
            Files.createDirectories(databasePath);

            // Use a temporary QR directory; once we know the phone after auth, the
            // directory is renamed to data-{phone} by updateAccountInfo().
            this.currentDataSubDir = "data-qr";

            APIToken apiToken = new APIToken(apiId, apiHash);
            TDLibSettings settings = TDLibSettings.create(apiToken);
            settings.setDatabaseDirectoryPath(databasePath.resolve("data-qr"));
            settings.setDownloadedFilesDirectoryPath(databasePath.resolve("downloads-data-qr"));

            if (clientFactory == null) {
                clientFactory = new SimpleTelegramClientFactory();
            }

            SimpleTelegramClientBuilder builder = clientFactory.builder(settings);
            builder.setClientInteraction(this);
            builder.addUpdateHandler(TdApi.UpdateAuthorizationState.class, this::onAuthorizationStateUpdate);
            builder.addDefaultExceptionHandler(this::onExceptionHandler);
            builder.addUpdateExceptionHandler(this::onExceptionHandler);

            AuthenticationSupplier<?> authenticationData = AuthenticationSupplier.qrCode();
            this.client = builder.build(authenticationData);

            // Wait up to 10 seconds for TDLib to connect and issue QR confirmation link
            for (int i = 0; i < 100; i++) {
                if ("WAITING_FOR_QR".equals(currentStatus) || "READY".equals(currentStatus) || "ERROR".equals(currentStatus)) {
                    break;
                }
                Thread.sleep(100);
            }

            return ResponseEntity.ok(getSnapshot());
        } catch (Exception e) {
            log.error("Failed to initiate Telegram QR Code login", e);
            this.lastError = sanitizeErrorMessage(e.getMessage());
            this.currentStatus = "ERROR";
            return ResponseEntity.internalServerError().body(getSnapshot());
        }
    }

    private synchronized ResponseEntity<AuthResponse> initiateLoginFlow(String rawPhone) {
        if (rawPhone == null || rawPhone.trim().isEmpty()) {
            this.lastError = "Phone number cannot be empty.";
            this.currentStatus = "ERROR";
            return ResponseEntity.badRequest().body(getSnapshot());
        }

        // Clean formatting from phone number
        String phone = rawPhone.replaceAll("[\\s()\\-_]", "").trim();
        if (!phone.startsWith("+")) {
            phone = "+" + phone;
        }

        if (!phone.matches("^\\+[0-9]{7,16}$")) {
            this.lastError = "Please enter a valid international phone number with country code (e.g. +919876543210).";
            this.currentStatus = "ERROR";
            return ResponseEntity.badRequest().body(getSnapshot());
        }

        this.currentPhoneNumber = phone;
        this.lastError = null;
        this.currentStatus = "STARTING";
        this.codeType = "";
        this.codeDescription = "";
        this.accountName = "";

        log.info("Initiating Telegram login for phone: {}", phone);

        try {
            String dataDir = dataSubDir(phone);
            this.currentDataSubDir = dataDir;
            startClientWithPhone(phone, dataDir);

            // Wait up to 10 seconds for TDLib to connect and request the OTP
            for (int i = 0; i < 100; i++) {
                if ("WAITING_FOR_CODE".equals(currentStatus) || "READY".equals(currentStatus) || "ERROR".equals(currentStatus)) {
                    break;
                }
                Thread.sleep(100);
            }

            return ResponseEntity.ok(getSnapshot());
        } catch (Exception e) {
            log.error("Failed to initiate Telegram login", e);
            this.lastError = sanitizeErrorMessage(e.getMessage());
            this.currentStatus = "ERROR";
            return ResponseEntity.internalServerError().body(getSnapshot());
        }
    }

    /**
     * Creates and starts the TDLib client.
     *
     * @param phone   Telegram phone number for authentication (may be null/empty for session resume)
     * @param dataDir Subdirectory of {@code dbPath} to use as TDLib database (per-user isolation)
     */
    private synchronized void startClientWithPhone(String phone, String dataDir) throws Exception {
        closeExistingClient();

        Path databasePath = Paths.get(dbPath).toAbsolutePath().normalize();
        Files.createDirectories(databasePath);

        APIToken apiToken = new APIToken(apiId, apiHash);
        TDLibSettings settings = TDLibSettings.create(apiToken);
        // Per-user isolation: each phone number gets its own TDLib DB subdirectory
        settings.setDatabaseDirectoryPath(databasePath.resolve(dataDir));
        settings.setDownloadedFilesDirectoryPath(databasePath.resolve("downloads-" + dataDir));

        if (clientFactory == null) {
            clientFactory = new SimpleTelegramClientFactory();
        }

        SimpleTelegramClientBuilder builder = clientFactory.builder(settings);
        builder.setClientInteraction(this);
        builder.addUpdateHandler(TdApi.UpdateAuthorizationState.class, this::onAuthorizationStateUpdate);
        builder.addDefaultExceptionHandler(this::onExceptionHandler);
        builder.addUpdateExceptionHandler(this::onExceptionHandler);

        // Provide the phone to AuthenticationSupplier; TDLib will use the cached
        // session if valid and ignore the phone. The phone is only used if TDLib
        // needs to re-authenticate (e.g. after session expiry).
        String effectivePhone = (phone != null && !phone.trim().isEmpty()) ? phone
                : (currentPhoneNumber != null && !currentPhoneNumber.isEmpty() ? currentPhoneNumber : "+1");
        this.client = builder.build(AuthenticationSupplier.user(effectivePhone));
    }

    private void savePhoneToFile(String phone) {
        if (phone == null || phone.isBlank()) return;
        try {
            Path phonePath = Paths.get(dbPath).toAbsolutePath().normalize().resolve("phone.txt");
            Files.writeString(phonePath, phone);
            log.info("Phone number saved for future auto-resume.");
        } catch (Exception e) {
            log.warn("Could not save phone number to file: {}", e.getMessage());
        }
    }

    private String loadSavedPhone() {
        try {
            Path phonePath = Paths.get(dbPath).toAbsolutePath().normalize().resolve("phone.txt");
            if (Files.exists(phonePath)) {
                String phone = Files.readString(phonePath).trim();
                if (!phone.isBlank()) {
                    log.info("Loaded saved phone for auto-resume: {}", phone);
                    return phone;
                }
            }
        } catch (Exception e) {
            log.warn("Could not load saved phone number: {}", e.getMessage());
        }
        return null;
    }

    private synchronized ResponseEntity<AuthResponse> handleOtpSubmission(String rawCode) {
        if (rawCode == null || rawCode.trim().isEmpty()) {
            this.lastError = "Please enter the verification code.";
            return ResponseEntity.badRequest().body(getSnapshot());
        }
        String code = rawCode.trim();
        log.info("Submitting verification code...");

        // Clear any error left over from a previous failed attempt so the
        // wait loop below doesn't break immediately on a stale error.
        this.lastError = null;

        CompletableFuture<String> pending = codeFuture.getAndSet(null);
        if (pending != null && !pending.isDone()) {
            pending.complete(code);
        } else if (client != null) {
            // codeFuture was already consumed (e.g. after a previous invalid
            // code TDLib re-issues ASK_CODE and a new future is set), send
            // the code directly via CheckAuthenticationCode as a fallback.
            client.send(new TdApi.CheckAuthenticationCode(code))
                    .whenComplete((ok, ex) -> {
                        if (ex != null) {
                            onExceptionHandler(ex);
                        }
                    });
        } else {
            this.lastError = "No active login session. Please start over.";
            this.currentStatus = "ERROR";
            return ResponseEntity.badRequest().body(getSnapshot());
        }

        // Wait up to 5 seconds for a terminal state
        for (int i = 0; i < 50; i++) {
            if ("READY".equals(currentStatus) || "WAITING_FOR_PASSWORD".equals(currentStatus) || lastError != null) {
                break;
            }
            try {
                Thread.sleep(100);
            } catch (InterruptedException ignored) {
                Thread.currentThread().interrupt();
            }
        }

        return ResponseEntity.ok(getSnapshot());
    }

    private synchronized ResponseEntity<AuthResponse> handlePasswordSubmission(String rawPassword) {
        if (rawPassword == null || rawPassword.trim().isEmpty()) {
            this.lastError = "Please enter your 2FA password.";
            return ResponseEntity.badRequest().body(getSnapshot());
        }
        String password = rawPassword.trim();
        log.info("Submitting 2FA cloud password...");

        CompletableFuture<String> pending = passwordFuture.getAndSet(null);
        if (pending != null && !pending.isDone()) {
            pending.complete(password);
        } else if (client != null) {
            client.send(new TdApi.CheckAuthenticationPassword(password))
                    .whenComplete((ok, ex) -> {
                        if (ex != null) {
                            onExceptionHandler(ex);
                        }
                    });
        } else {
            this.lastError = "No active login session. Please start over.";
            this.currentStatus = "ERROR";
            return ResponseEntity.badRequest().body(getSnapshot());
        }

        // Wait briefly for state change
        for (int i = 0; i < 20; i++) {
            if ("READY".equals(currentStatus) || lastError != null) {
                break;
            }
            try {
                Thread.sleep(100);
            } catch (InterruptedException ignored) {
                Thread.currentThread().interrupt();
            }
        }

        return ResponseEntity.ok(getSnapshot());
    }

    private synchronized ResponseEntity<AuthResponse> handleResendOtp() {
        if (client == null) {
            this.lastError = "No active login session.";
            return ResponseEntity.badRequest().body(getSnapshot());
        }
        log.info("Requesting OTP resend from Telegram...");
        client.send(new TdApi.ResendAuthenticationCode())
                .whenComplete((ok, ex) -> {
                    if (ex != null) {
                        onExceptionHandler(ex);
                    } else {
                        log.info("Authentication code resent successfully");
                    }
                });
        return ResponseEntity.ok(getSnapshot());
    }

    private synchronized ResponseEntity<AuthResponse> handleLogout() {
        log.info("Hard logout: revoking Telegram session and wiping local database...");

        // 1. Tell Telegram to invalidate the server-side session token
        if (client != null) {
            try {
                client.send(new TdApi.LogOut()).get(5, TimeUnit.SECONDS);
            } catch (Exception ignored) {
                // LogOut may throw if the client is already closing; proceed anyway
            }
        }

        // 2. Close the TDLib client gracefully
        closeExistingClient();

        // 3. Delete the on-disk session database so no cached credentials remain
        wipeDatabase();

        this.currentStatus = "LOGGED_OUT";
        this.currentPhoneNumber = "";
        this.accountName = "";
        this.qrCodeLink = "";
        this.lastError = null;
        log.info("Hard logout complete. Next login will require a fresh OTP.");
        return ResponseEntity.ok(getSnapshot());
    }

    @Override
    public CompletableFuture<String> onParameterRequest(InputParameter parameter, ParameterInfo parameterInfo) {
        log.info("TDLib requested parameter: {}", parameter);
        switch (parameter) {
            case ASK_CODE, ASK_EMAIL_CODE -> {
                this.currentStatus = "WAITING_FOR_CODE";
                this.lastError = null;
                CompletableFuture<String> future = new CompletableFuture<>();
                CompletableFuture<String> prev = codeFuture.getAndSet(future);
                if (prev != null && !prev.isDone()) {
                    prev.completeExceptionally(new IllegalStateException("Replaced by new code request"));
                }
                return future;
            }
            case ASK_PASSWORD -> {
                this.currentStatus = "WAITING_FOR_PASSWORD";
                this.lastError = null;
                CompletableFuture<String> future = new CompletableFuture<>();
                CompletableFuture<String> prev = passwordFuture.getAndSet(future);
                if (prev != null && !prev.isDone()) {
                    prev.completeExceptionally(new IllegalStateException("Replaced by new password request"));
                }
                return future;
            }
            case NOTIFY_LINK -> {
                return CompletableFuture.completedFuture("");
            }
            default -> {
                String msg = "Unsupported parameter request: " + parameter;
                log.warn(msg);
                this.lastError = msg;
                this.currentStatus = "ERROR";
                return CompletableFuture.failedFuture(new UnsupportedOperationException(msg));
            }
        }
    }

    private void onAuthorizationStateUpdate(TdApi.UpdateAuthorizationState update) {
        TdApi.AuthorizationState state = update.authorizationState;
        log.info("TDLib Authorization state updated: {}", state.getClass().getSimpleName());

        if (state instanceof TdApi.AuthorizationStateWaitOtherDeviceConfirmation waitOther) {
            this.currentStatus = "WAITING_FOR_QR";
            this.lastError = null;
            this.qrCodeLink = waitOther.link;
            this.codeDescription = "Open Telegram on your mobile phone, go to Settings -> Devices -> Add Device and scan the QR code to log in.";
            log.info(">>> QR Code login link received: {}", waitOther.link);
        } else if (state instanceof TdApi.AuthorizationStateWaitCode waitCode) {
            this.currentStatus = "WAITING_FOR_CODE";
            this.lastError = null;
            this.codeTimeout = waitCode.codeInfo.timeout;

            TdApi.AuthenticationCodeType type = waitCode.codeInfo.type;
            if (type instanceof TdApi.AuthenticationCodeTypeTelegramMessage) {
                this.codeType = "telegram";
                this.codeDescription = "We have sent a code to the Telegram app on your other device.";
            } else if (type instanceof TdApi.AuthenticationCodeTypeSms) {
                this.codeType = "sms";
                this.codeDescription = "We have sent a verification code via SMS to your phone.";
            } else if (type instanceof TdApi.AuthenticationCodeTypeCall) {
                this.codeType = "call";
                this.codeDescription = "Telegram will call your phone with the verification code.";
            } else if (type instanceof TdApi.AuthenticationCodeTypeFlashCall) {
                this.codeType = "flash_call";
                this.codeDescription = "Telegram is calling your phone to verify the number.";
            } else if (type instanceof TdApi.AuthenticationCodeTypeMissedCall) {
                this.codeType = "missed_call";
                this.codeDescription = "Telegram will make a missed call to verify your number.";
            } else {
                this.codeType = "other";
                this.codeDescription = "Verification code has been sent via: " + type.getClass().getSimpleName();
            }

            log.info(">>> Code sent via: {} ({})", type.getClass().getSimpleName(), codeDescription);
        } else if (state instanceof TdApi.AuthorizationStateWaitPassword waitPassword) {
            this.currentStatus = "WAITING_FOR_PASSWORD";
            this.lastError = null;
            log.info(">>> Two-step verification password required. Hint: {}", waitPassword.passwordHint);
        } else if (state instanceof TdApi.AuthorizationStateReady) {
            this.currentStatus = "READY";
            this.lastError = null;
            updateAccountInfo();
            // Persist the phone number so auto-resume after restart can reuse it
            if (currentPhoneNumber != null && !currentPhoneNumber.isEmpty()) {
                savePhoneToFile(currentPhoneNumber);
            }
            log.info(">>> Authorization State READY! User is authenticated.");
        } else if (state instanceof TdApi.AuthorizationStateClosed) {
            this.currentStatus = "LOGGED_OUT";
            log.info(">>> Authorization State CLOSED.");
        }
    }

    private void onExceptionHandler(Throwable throwable) {
        if (throwable == null) return;
        String rawMsg = throwable.getMessage() == null ? throwable.toString() : throwable.getMessage();
        log.error("TDLib exception: {}", rawMsg);
        this.lastError = sanitizeErrorMessage(rawMsg);
        // If TDLib fails while still starting up, move to ERROR so the frontend
        // stops polling and shows the login form instead of loading forever.
        if ("STARTING".equals(currentStatus) || "IDLE".equals(currentStatus) || "WAITING_FOR_QR".equals(currentStatus)) {
            this.currentStatus = "ERROR";
        }
    }

    private void updateAccountInfo() {
        if (client != null) {
            client.getMeAsync().whenComplete((user, ex) -> {
                if (ex == null && user != null) {
                    String first = user.firstName == null ? "" : user.firstName;
                    String last = user.lastName == null ? "" : user.lastName;
                    String name = (first + " " + last).trim();
                    this.accountName = name.isEmpty() ? "Telegram User" : name;
                    if (user.phoneNumber != null && !user.phoneNumber.isEmpty()) {
                        this.currentPhoneNumber = user.phoneNumber.startsWith("+") ? user.phoneNumber : "+" + user.phoneNumber;
                        // Update the per-user DB directory tracking (critical for QR login
                        // which starts with a temporary "data-qr" directory)
                        this.currentDataSubDir = dataSubDir(this.currentPhoneNumber);
                        // Persist the resolved phone number for future auto-resume
                        savePhoneToFile(this.currentPhoneNumber);
                    }
                    log.info("Logged in as: {} ({})", this.accountName, this.currentPhoneNumber);
                }
            });
        }
    }

    private void closeExistingClient() {
        if (client != null) {
            try {
                client.closeAsync()
                        .exceptionally(t -> null)
                        .thenCompose(v -> client.waitForExitAsync())
                        .exceptionally(t -> null)
                        .get(5, TimeUnit.SECONDS);
            } catch (Exception ignored) {
            }
            client = null;
        }
    }

    private void wipeDatabase() {
        try {
            Path databasePath = Paths.get(dbPath).toAbsolutePath().normalize();
            // Only wipe the current user's data directory, not others'
            Path dataPath = databasePath.resolve(currentDataSubDir);
            if (Files.exists(dataPath)) {
                Files.walk(dataPath)
                        .sorted(Comparator.reverseOrder())
                        .forEach(p -> {
                            try { Files.delete(p); } catch (IOException ignored) {}
                        });
                log.info("Session database wiped: {}", dataPath);
            }
            // Also remove the downloads dir for this user
            Path downloadsPath = databasePath.resolve("downloads-" + currentDataSubDir);
            if (Files.exists(downloadsPath)) {
                Files.walk(downloadsPath)
                        .sorted(Comparator.reverseOrder())
                        .forEach(p -> {
                            try { Files.delete(p); } catch (IOException ignored) {}
                        });
            }
            // Clear phone.txt so auto-resume won't try to resume the wiped session
            Files.deleteIfExists(databasePath.resolve("phone.txt"));
        } catch (IOException e) {
            log.warn("Could not fully wipe session database: {}", e.getMessage());
        }
    }

    private AuthResponse getSnapshot() {
        return new AuthResponse(
                currentStatus,
                codeType,
                codeDescription,
                codeTimeout,
                accountName,
                lastError,
                currentPhoneNumber,
                qrCodeLink
        );
    }

    private String sanitizeErrorMessage(String msg) {
        if (msg == null || msg.trim().isEmpty()) {
            return "An unexpected Telegram error occurred.";
        }
        if (msg.contains("AUTH_TOKEN_EXPIRED") || msg.contains("EXPIRED")) {
            return "QR login token expired.";
        }
        if (msg.contains("PHONE_NUMBER_INVALID")) {
            return "The phone number entered is invalid for Telegram. Please check the country code and number.";
        }
        if (msg.contains("PHONE_CODE_INVALID")) {
            return "Invalid verification code. Please check and try again.";
        }
        if (msg.contains("PHONE_CODE_EXPIRED")) {
            return "Verification code has expired. Please request a new code.";
        }
        if (msg.contains("PASSWORD_HASH_INVALID")) {
            return "Incorrect Two-Step Verification cloud password.";
        }
        if (msg.contains("FLOOD_WAIT")) {
            return "Too many attempts. Telegram has temporarily rate-limited your account. Please wait a while.";
        }
        if (msg.contains("Can't lock") || msg.contains("database")) {
            return "Telegram session database is busy. Please wait a moment and try again.";
        }
        return msg;
    }

    public SimpleTelegramClient getClient() {
        return client;
    }

    public String getCurrentStatus() {
        return currentStatus;
    }

    public String getCurrentPhoneNumber() {
        return currentPhoneNumber;
    }

    public String getAccountName() {
        return accountName;
    }

    public record AuthResponse(
            String status,
            String codeType,
            String codeDescription,
            int timeout,
            String accountName,
            String error,
            String phoneNumber,
            String qrCodeLink
    ) {}
}