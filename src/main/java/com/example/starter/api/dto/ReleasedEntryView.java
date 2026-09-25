package com.example.starter.api.dto;

/**
 * 发布快照中的固化条目：记录发布时命中的证明版本与构建摘要。
 */
public record ReleasedEntryView(
        String name,
        int version,
        long attestationId,
        String sourceRepository,
        String buildDigest,
        int attestationLevel) {
}
