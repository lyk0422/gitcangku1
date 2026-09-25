package com.example.starter.api.dto;

/**
 * 发布快照中的单个坐标来源条目（发布时冻结）。
 */
public record PublishEntryView(
        String name,
        int version,
        int attestationVersion,
        String repoId,
        String digest,
        int level) {
}
