package com.example.demo.auth;

public record AuthSnapshot(
        LoginStep step,
        String qrLink,
        String accountName,
        String error
) {
    public boolean isReady() {
        return step == LoginStep.READY;
    }
}
