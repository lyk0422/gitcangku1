package com.example.starter.observation;

import java.time.Instant;

/**
 * 墓碑恢复历史响应：一条不可变恢复记录的对外表示，记录恢复前后版本、来源版本、原因与 UTC 时刻。
 *
 * @param observationId    观测记录唯一标识
 * @param previousVersion  恢复前墓碑版本号
 * @param recoveredVersion 恢复后生成的新当前版本号（墓碑版本 + 1）
 * @param sourceVersion    恢复内容来源的历史非墓碑版本号（可跨代次）
 * @param generationBefore 恢复前合并代次
 * @param generationAfter  恢复后合并代次
 * @param reason           恢复原因（非空）
 * @param requestId        生成该恢复记录的请求标识
 * @param recoveredAtUtc   恢复完成时刻（UTC，ISO-8601）
 */
public record RecoveryResponse(
        String observationId,
        int previousVersion,
        int recoveredVersion,
        int sourceVersion,
        int generationBefore,
        int generationAfter,
        String reason,
        String requestId,
        Instant recoveredAtUtc) {

    /**
     * 由不可变恢复记录构造响应。
     */
    public static RecoveryResponse of(RecoveryRecord record) {
        return new RecoveryResponse(
                record.observationId(),
                record.previousVersion(),
                record.recoveredVersion(),
                record.sourceVersion(),
                record.generationBefore(),
                record.generationAfter(),
                record.reason(),
                record.requestId(),
                record.recoveredAtUtc());
    }
}
