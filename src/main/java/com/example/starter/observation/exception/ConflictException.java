package com.example.starter.observation.exception;

import java.util.List;

/**
 * 三方合并存在字段冲突（HTTP 409），携带冲突字段与当前版本；
 * 或同一 requestId 异参重放（冲突字段为空）。
 */
public class ConflictException extends RuntimeException {

    private final int currentVersion;
    private final List<String> conflictFields;

    public ConflictException(int currentVersion, List<String> conflictFields) {
        super("字段合并冲突");
        this.currentVersion = currentVersion;
        this.conflictFields = conflictFields;
    }

    public ConflictException(String message) {
        super(message);
        this.currentVersion = 0;
        this.conflictFields = List.of();
    }

    public int getCurrentVersion() {
        return currentVersion;
    }

    public List<String> getConflictFields() {
        return conflictFields;
    }
}
