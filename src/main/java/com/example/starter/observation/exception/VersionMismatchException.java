package com.example.starter.observation.exception;

/**
 * expectedVersion/baseVersion 与当前版本不匹配（HTTP 409）。
 */
public class VersionMismatchException extends RuntimeException {

    private final int currentVersion;

    public VersionMismatchException(int currentVersion, String message) {
        super(message);
        this.currentVersion = currentVersion;
    }

    public int getCurrentVersion() {
        return currentVersion;
    }
}
