package com.example.starter.germination.domain;

import java.time.Instant;

/**
 * 批次放行事实；一旦生成即历史事实，不因后续检测作废而回滚。
 *
 * @param batchNo     被放行的种子批次编号（主键，一个批次至多放行一次）
 * @param certNo      放行时依据的证书编号（证书快照外键）
 * @param releasedAt  放行时刻（UTC 时间线）
 */
public record BatchRelease(String batchNo, String certNo, Instant releasedAt) {
}
