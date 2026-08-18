package com.example.demo.auth;

import com.example.demo.web.ApiException;
import it.tdlight.client.ClientInteraction;
import it.tdlight.client.InputParameter;
import it.tdlight.client.ParameterInfo;
import it.tdlight.client.ParameterInfoNotifyLink;
import it.tdlight.client.SimpleTelegramClient;
import it.tdlight.jni.TdApi;
import java.nio.file.Path;
import java.util.Objects;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.atomic.AtomicReference;

/** State kept only for the browser's server-side HTTP session. */
public final class TelegramAccountSession implements ClientInteraction {

    private final String sessionKey;
    private final Path storageDirectory;
    private final AtomicReference<CompletableFuture<String>> codeFuture = new AtomicReference<>();
    private final AtomicReference<CompletableFuture<String>> passwordFuture = new AtomicReference<>();

    private volatile SimpleTelegramClient client;
    private volatile LoginStep step = LoginStep.STARTING;
    private volatile String qrLink;
    private volatile String accountName;
    private volatile String error;

    TelegramAccountSession(String sessionKey, Path storageDirectory) {
        this.sessionKey = Objects.requireNonNull(sessionKey);
        this.storageDirectory = Objects.requireNonNull(storageDirectory);
    }

    public String sessionKey() {
        return sessionKey;
    }

    public Path storageDirectory() {
        return storageDirectory;
    }

    public SimpleTelegramClient client() {
        if (client == null) {
            throw ApiException.serviceUnavailable("Telegram is still starting. Please wait a moment.");
        }
        return client;
    }

    void attach(SimpleTelegramClient client) {
        this.client = client;
        if (step == LoginStep.READY) {
            updateAccountName();
        }
    }

    public AuthSnapshot snapshot() {
        return new AuthSnapshot(step, qrLink, accountName, error);
    }

    public boolean isReady() {
        return step == LoginStep.READY;
    }

    public void submitCode(String rawCode) {
        complete(codeFuture, rawCode, "Enter the code Telegram sent you.");
    }

    public void submitPassword(String password) {
        complete(passwordFuture, password, "Enter your Telegram two-step verification password.");
    }

    public void onAuthorizationState(TdApi.UpdateAuthorizationState update) {
        TdApi.AuthorizationState state = update.authorizationState;
        if (state instanceof TdApi.AuthorizationStateReady) {
            step = LoginStep.READY;
            error = null;
            qrLink = null;
            updateAccountName();
        } else if (state instanceof TdApi.AuthorizationStateWaitOtherDeviceConfirmation confirmation) {
            qrLink = confirmation.link;
            step = LoginStep.WAITING_FOR_QR;
        } else if (state instanceof TdApi.AuthorizationStateWaitCode) {
            step = LoginStep.WAITING_FOR_CODE;
        } else if (state instanceof TdApi.AuthorizationStateWaitPassword) {
            step = LoginStep.WAITING_FOR_PASSWORD;
        } else if (state instanceof TdApi.AuthorizationStateClosed) {
            step = LoginStep.LOGGED_OUT;
        }
    }

    public void onFailure(Throwable throwable) {
        error = sanitize(throwable == null ? null : throwable.getMessage());
        step = LoginStep.ERROR;
    }

    @Override
    public CompletableFuture<String> onParameterRequest(InputParameter parameter, ParameterInfo parameterInfo) {
        return switch (parameter) {
            case NOTIFY_LINK -> {
                if (parameterInfo instanceof ParameterInfoNotifyLink linkInfo) {
                    qrLink = linkInfo.getLink();
                    step = LoginStep.WAITING_FOR_QR;
                }
                yield CompletableFuture.completedFuture("");
            }
            case ASK_CODE, ASK_EMAIL_CODE -> waitFor(codeFuture, LoginStep.WAITING_FOR_CODE);
            case ASK_PASSWORD -> waitFor(passwordFuture, LoginStep.WAITING_FOR_PASSWORD);
            default -> {
                error = "Telegram needs an additional sign-in step (" + parameter.name().toLowerCase().replace('_', ' ') + "). Complete it in Telegram and start the login again.";
                step = LoginStep.ERROR;
                yield CompletableFuture.failedFuture(new IllegalStateException(error));
            }
        };
    }

    public void close() {
        SimpleTelegramClient currentClient = client;
        if (currentClient != null) {
            currentClient.closeAsync()
                    .exceptionally(ignored -> null)
                    .thenCompose(ignored -> currentClient.waitForExitAsync())
                    .exceptionally(ignored -> null)
                    .join();
        }
        step = LoginStep.LOGGED_OUT;
    }

    public void logOut() {
        SimpleTelegramClient currentClient = client;
        if (currentClient != null) {
            currentClient.logOutAsync().exceptionally(ignored -> null);
        }
        step = LoginStep.LOGGED_OUT;
    }

    private CompletableFuture<String> waitFor(AtomicReference<CompletableFuture<String>> slot, LoginStep nextStep) {
        CompletableFuture<String> next = new CompletableFuture<>();
        CompletableFuture<String> previous = slot.getAndSet(next);
        if (previous != null && !previous.isDone()) {
            previous.completeExceptionally(new IllegalStateException("A newer sign-in prompt replaced this one."));
        }
        step = nextStep;
        return next;
    }

    private void complete(AtomicReference<CompletableFuture<String>> slot, String value, String emptyMessage) {
        String cleaned = value == null ? "" : value.trim();
        if (cleaned.isBlank()) {
            throw ApiException.badRequest(emptyMessage);
        }
        CompletableFuture<String> pending = slot.getAndSet(null);
        if (pending == null || pending.isDone()) {
            throw ApiException.conflict("Telegram is not waiting for that value yet.");
        }
        pending.complete(cleaned);
    }

    private void updateAccountName() {
        SimpleTelegramClient currentClient = client;
        if (currentClient == null) {
            return;
        }
        currentClient.getMeAsync().whenComplete((user, throwable) -> {
            if (throwable == null && user != null) {
                String fullName = (user.firstName == null ? "" : user.firstName) + " " + (user.lastName == null ? "" : user.lastName);
                accountName = fullName.trim().isBlank() ? "Telegram account" : fullName.trim();
            }
        });
    }

    private String sanitize(String message) {
        if (message == null || message.isBlank()) {
            return "Telegram could not complete the sign-in. Please try again.";
        }
        return message.length() > 220 ? message.substring(0, 220) : message;
    }
}
