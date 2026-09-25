package com.example.starter.api.dto;

import java.util.List;

/**
 * 发布快照中单张锁定图的固化视图。
 */
public record PublishLockView(
        long lockFileId,
        String rootName,
        int rootVersion,
        long repositoryVersion,
        List<PublishEntryView> entries) {
}
