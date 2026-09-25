package com.example.starter.api.dto;

import java.time.Instant;
import java.util.List;

/**
 * 锁定图发布结果：固化策略版本、证明版本与 provenanceKey 指纹。
 */
public record PublishResponse(
        long id,
        long lockFileId,
        int policyVersion,
        String provenanceKey,
        String operator,
        Instant publishedAt,
        List<PublishEntryView> entries) {
}
