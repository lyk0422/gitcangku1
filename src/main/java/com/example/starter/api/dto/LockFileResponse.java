package com.example.starter.api.dto;

import java.time.Instant;
import java.util.List;

/**
 * 历史锁文件视图，entries 按名称升序，substitutions 按步骤序号升序。
 *
 * @param platform       目标平台，null 表示未启用替代
 * @param policyVersion  锁定快照冻结的策略版本号，null 表示无生效策略
 * @param substitutions  替代解释步骤快照，历史策略修改、撤回或恢复均不改写
 */
public record LockFileResponse(
        long id,
        String rootName,
        int rootVersion,
        long repositoryVersion,
        Instant createdAt,
        List<LockEntryResponse> entries,
        String platform,
        Long policyVersion,
        List<SubstitutionStepResponse> substitutions) {

    public LockFileResponse(long id, String rootName, int rootVersion, long repositoryVersion,
                            Instant createdAt, List<LockEntryResponse> entries) {
        this(id, rootName, rootVersion, repositoryVersion, createdAt, entries, null, null, List.of());
    }
}
