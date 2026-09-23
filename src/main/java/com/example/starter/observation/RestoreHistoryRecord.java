package com.example.starter.observation;

import java.time.Instant;

/**
 * 不可变墓碑恢复历史记录：对应 restore_history 表的一行，恢复成功后原子追加，永不修改。
 *
 * @param observationId       观测记录唯一标识
 * @param requestId           生成该恢复记录的请求标识
 * @param previousVersion     恢复前当前墓碑版本号
 * @param newVersion          恢复生成的新版本号（墓碑版本号 + 1，不回退）
 * @param sourceVersion       恢复内容来源的历史非墓碑版本号（可跨代次）
 * @param previousGeneration  恢复前合并代次
 * @param newGeneration       恢复后合并代次（恢复前代次 + 1）
 * @param reason              非空恢复原因
 * @param restoredAtUtc       恢复完成时刻（UTC）
 */
public record RestoreHistoryRecord(
        String observationId,
        String requestId,
        int previousVersion,
        int newVersion,
        int sourceVersion,
        int previousGeneration,
        int newGeneration,
        String reason,
        Instant restoredAtUtc) {
}
