package com.example.starter.observation;

import java.time.Instant;

/**
 * 不可变附页撤销记录：对应 corrigendum_revocation 表的一行，撤销成功后原子落库，永不修改。
 *
 * @param revocationId       全局唯一撤销记录标识
 * @param observationId      观测记录唯一标识
 * @param corrVersion        被撤销的附页版本号
 * @param restoredCorrVersion 撤销后恢复到的有效附页版本；null 表示无有效附页、恢复原始观测值
 * @param corrKey            撤销请求幂等键
 * @param operator           执行撤销的操作者标识
 * @param reason             撤销原因；null 表示未填写
 * @param revokedAtUtc       撤销完成时刻（UTC）
 */
public record RevocationRecord(
        String revocationId,
        String observationId,
        int corrVersion,
        Integer restoredCorrVersion,
        String corrKey,
        String operator,
        String reason,
        Instant revokedAtUtc) {
}
