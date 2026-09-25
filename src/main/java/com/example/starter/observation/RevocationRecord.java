package com.example.starter.observation;

import java.time.Instant;

/**
 * 附页撤销记录：对应 corrigendum_revocation 表的一行，不可变、永不更新或删除。
 *
 * @param observationId 观测记录唯一标识
 * @param corrVersion   被撤销的附页版本号
 * @param requestId     执行撤销的请求标识
 * @param operator      执行撤销的操作者标识
 * @param revokedAtUtc  撤销完成时刻（UTC）
 */
public record RevocationRecord(
        String observationId,
        int corrVersion,
        String requestId,
        String operator,
        Instant revokedAtUtc) {
}
