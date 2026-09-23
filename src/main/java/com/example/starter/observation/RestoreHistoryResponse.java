package com.example.starter.observation;

import java.time.Instant;

/**
 * 墓碑恢复历史响应：返回一次成功恢复的前后版本、来源版本、代次变化、原因与 UTC 时刻。
 *
 * @param observationId      观测记录唯一标识
 * @param requestId          生成该恢复记录的请求标识
 * @param previousVersion    恢复前当前墓碑版本号
 * @param newVersion         恢复生成的新版本号
 * @param sourceVersion      恢复内容来源的历史非墓碑版本号（可跨代次）
 * @param previousGeneration 恢复前合并代次
 * @param newGeneration      恢复后合并代次
 * @param reason             恢复原因
 * @param restoredAtUtc      恢复完成时刻（UTC，ISO-8601）
 */
public record RestoreHistoryResponse(
        String observationId,
        String requestId,
        int previousVersion,
        int newVersion,
        int sourceVersion,
        int previousGeneration,
        int newGeneration,
        String reason,
        Instant restoredAtUtc) {

    /**
     * 由不可变恢复历史记录构造响应。
     */
    public static RestoreHistoryResponse of(RestoreHistoryRecord record) {
        return new RestoreHistoryResponse(
                record.observationId(),
                record.requestId(),
                record.previousVersion(),
                record.newVersion(),
                record.sourceVersion(),
                record.previousGeneration(),
                record.newGeneration(),
                record.reason(),
                record.restoredAtUtc());
    }
}
