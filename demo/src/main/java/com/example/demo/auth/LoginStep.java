package com.example.demo.auth;

public enum LoginStep {
    IDLE,
    STARTING,
    WAITING_FOR_QR,
    WAITING_FOR_CODE,
    WAITING_FOR_PASSWORD,
    READY,
    ERROR,
    LOGGED_OUT
}
