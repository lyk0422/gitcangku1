package com.example.starter.observation.dto;

import java.util.List;

/**
 * 三方合并冲突响应：列出冲突字段及当前版本。
 */
public class ConflictResponse {

    private int currentVersion;
    private List<String> conflictFields;

    public ConflictResponse() {
    }

    public ConflictResponse(int currentVersion, List<String> conflictFields) {
        this.currentVersion = currentVersion;
        this.conflictFields = conflictFields;
    }

    public int getCurrentVersion() {
        return currentVersion;
    }

    public void setCurrentVersion(int currentVersion) {
        this.currentVersion = currentVersion;
    }

    public List<String> getConflictFields() {
        return conflictFields;
    }

    public void setConflictFields(List<String> conflictFields) {
        this.conflictFields = conflictFields;
    }
}
