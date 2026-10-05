package com.example.starter.observation;

import java.time.Instant;
import java.util.List;

/**
 * 未解决冲突诊断记录：对应 open_conflict 表的一行；合并判定冲突时登记，显式解决成功后清除。
 *
 * @param observationId  观测记录唯一标识
 * @param conflictFields 未解决冲突字段名列表（固定顺序 location/reading/note）
 * @param baseVersion    冲突判定时的基线版本号
 * @param currentVersion 冲突判定时的当前版本号
 * @param requestId      触发冲突判定的合并请求标识
 * @param detectedAtUtc  最近一次冲突检出时刻（UTC）
 */
public record OpenConflictRecord(
        String observationId,
        List<String> conflictFields,
        int baseVersion,
        int currentVersion,
        String requestId,
        Instant detectedAtUtc) {
}
