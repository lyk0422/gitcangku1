package com.example.starter.api.dto;

import java.time.Instant;
import java.util.List;

/**
 * 发布快照视图：发布后内容固化，策略收紧或证明撤销均不倒改。
 *
 * @param provenanceKey 来源指纹：含锁定图版本、策略版本、规范化证明摘要与操作者
 */
public record ReleaseSnapshotResponse(
        long id,
        long lockFileId,
        String rootName,
        int rootVersion,
        int policyVersion,
        String provenanceKey,
        String operator,
        Instant createdAt,
        List<ReleasedEntryView> entries) {
}
