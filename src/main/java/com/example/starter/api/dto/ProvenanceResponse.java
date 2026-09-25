package com.example.starter.api.dto;

import java.util.List;

/**
 * 锁定图来源路径查询结果。
 *
 * @param published     true 表示已发布，entries 来自发布时冻结的快照
 * @param policyVersion 已发布时为固化的策略版本；未发布时为当前策略版本；无策略时为 null
 */
public record ProvenanceResponse(
        long lockFileId,
        boolean published,
        Integer policyVersion,
        List<ProvenanceEntryView> entries) {
}
