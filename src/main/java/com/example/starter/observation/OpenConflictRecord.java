package com.example.starter.observation;

import java.time.Instant;
import java.util.List;

/**
 * 未解决冲突状态：对应 open_conflict 表的一行；合并检出冲突时记录，合并/裁决/删除成功时清除。
 *
 * @param observationId  观测记录唯一标识
 * @param conflictFields 未解决冲突字段名列表（固定顺序 location/reading/note）
 * @param baseVersion    检出冲突时的基线版本号
 * @param currentVersion 检出冲突时的当前版本号
 * @param requestId      检出该冲突的请求标识
 * @param detectedAtUtc  冲突检出时刻（UTC）
 */
public record OpenConflictRecord(
        String observationId,
        List<String> conflictFields,
        int baseVersion,
        int currentVersion,
        String requestId,
        Instant detectedAtUtc) {
}