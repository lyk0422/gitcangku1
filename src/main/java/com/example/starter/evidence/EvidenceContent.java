package com.example.starter.evidence;

import java.time.LocalDateTime;

/**
 * 证物当前数字内容实体，对应 evidence_content 表。一件证物仅一行；
 * 重新登记内容时覆盖 content，历史封存版本不保留原文，只在 {@link SealedContent} 留存摘要。
 *
 * @param id          主键
 * @param evidenceKey 关联证物业务键
 * @param content     证物当前内容字节；重新登记内容时覆盖
 * @param updatedAt   最近一次内容登记时间（Asia/Shanghai）
 */
public record EvidenceContent(
        Long id,
        String evidenceKey,
        byte[] content,
        LocalDateTime updatedAt) {
}
