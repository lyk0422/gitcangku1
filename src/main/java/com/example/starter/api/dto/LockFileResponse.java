package com.example.starter.api.dto;

import java.time.Instant;
import java.util.List;

/**
 * 历史锁文件视图，entries 按名称升序、substitutionSteps 按步骤序号升序排列。
 *
 * @param policyVersion     锁定事务读取的唯一策略版本；从未发布策略时为 null
 * @param substitutionSteps 冻结的替代步骤解释快照
 */
public record LockFileResponse(
        long id,
        String rootName,
        int rootVersion,
        long repositoryVersion,
        String platform,
        Long policyVersion,
        Instant createdAt,
        List<LockEntryResponse> entries,
        List<SubstitutionStepResponse> substitutionSteps) {
}
