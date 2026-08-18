package com.example.demo.auth;

import com.example.demo.web.ApiException;

public record ApiCredentials(int apiId, String apiHash) {

    public ApiCredentials {
        if (apiId <= 0) {
            throw ApiException.badRequest("Telegram API ID must be a positive number.");
        }
        if (apiHash == null || !apiHash.matches("[A-Za-z0-9]{16,128}")) {
            throw ApiException.badRequest("Enter the API hash from my.telegram.org.");
        }
    }
}
