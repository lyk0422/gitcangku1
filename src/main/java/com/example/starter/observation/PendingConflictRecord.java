package com.example.starter.observation;

import java.time.Instant;

/**
 * 未解决冲突标记：对应 observation_pending_conflict 表的一行；
 * 显式解决或删除记录后清除。
 *
 * @param observationId      观测记录唯一标识
 * @param conflictFieldsJson 冲突字段名列表（JSON 数组原文）
 * @param baseVersion        发生冲突时客户端使用的基线版本号
 * @param currentVersion     发生冲突时服务端当前版本号
 * @param detectedAtUtc      最近一次冲突检出时刻（UTC）
 */
public record PendingConflictRecord(
        String observationId,
        String conflictFieldsJson,
        int baseVersion,
        int currentVersion,
        Instant detectedAtUtc) {
}