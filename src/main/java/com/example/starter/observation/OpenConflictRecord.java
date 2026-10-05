package com.example.starter.observation;

import java.time.Instant;
import java.util.List;

/**
 * 未解决冲突状态：对应 observation_open_conflict 表的一行。
 * 合并冲突（409）时落痕；其后任意一次成功的合并/解决/删除都会清除该状态。
 *
 * @param observationId  观测记录唯一标识（簇）
 * @param conflictFields 最近一次合并冲突的字段名列表
 * @param currentVersion 冲突发生时的当前版本号
 * @param detectedAtUtc  冲突检测时刻（UTC）
 */
public record OpenConflictRecord(
        String observationId,
        List<String> conflictFields,
        int currentVersion,
        Instant detectedAtUtc) {
}
