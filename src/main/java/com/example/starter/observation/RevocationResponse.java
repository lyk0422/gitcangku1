package com.example.starter.observation;

import com.fasterxml.jackson.annotation.JsonInclude;

import java.time.Instant;

/**
 * 附页撤销响应：返回不可变撤销记录要点。
 *
 * @param revocationId       全局唯一撤销记录标识
 * @param observationId      观测记录唯一标识
 * @param corrVersion        被撤销的附页版本号
 * @param restoredCorrVersion 撤销后恢复到的有效附页版本；null 表示恢复原始观测值
 * @param operator           执行撤销的操作者标识
 * @param reason             撤销原因（未填写时不返回）
 * @param revokedAtUtc       撤销完成时刻（UTC，ISO-8601）
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record RevocationResponse(
        String revocationId,
        String observationId,
        int corrVersion,
        Integer restoredCorrVersion,
        String operator,
        String reason,
        Instant revokedAtUtc) {

    /**
     * 由撤销记录构造响应。
     */
    public static RevocationResponse of(RevocationRecord record) {
        return new RevocationResponse(record.revocationId(), record.observationId(), record.corrVersion(),
                record.restoredCorrVersion(), record.operator(), record.reason(), record.revokedAtUtc());
    }
}
