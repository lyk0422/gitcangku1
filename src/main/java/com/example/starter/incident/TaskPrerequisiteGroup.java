package com.example.starter.incident;

import java.time.Instant;

/**
 * 择一前置组实体，对应 task_prerequisite_groups 表。
 * 每任务 1~3 组，配置替换时整组重建，仅 OPEN 任务可配置；
 * 完成判定要求每组至少一个 DONE 成员。selectedMemberId 为任务完成时冻结的
 * 选中依据成员（该组 DONE 成员中完整任务标识字典序最小者），未完成为空，冻结后不再变更。
 * 时间均为 UTC。
 */
public record TaskPrerequisiteGroup(
        long id,
        long taskId,
        int groupNo,
        Long selectedMemberId,
        Instant createdAt) {
}
