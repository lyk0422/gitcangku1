package com.example.starter.curtailment.commitment;

import java.math.BigDecimal;
import java.time.Instant;

/**
 * 站点容量承诺。
 *
 * @param id           主键
 * @param commitmentKey 承诺业务键，全局唯一
 * @param siteId       站点ID
 * @param validFrom    有效区间起点（UTC，含）
 * @param validTo      有效区间终点（UTC，不含）
 * @param maxPowerKw   最大削减功率，单位 kW，最多 3 位小数
 * @param status       状态：ACTIVE/SUSPENDED
 * @param createdAt    创建时间（UTC）
 * @param updatedAt    最近变更时间（UTC）
 */
public record Commitment(long id, String commitmentKey, String siteId, Instant validFrom, Instant validTo,
                         BigDecimal maxPowerKw, CommitmentStatus status, Instant createdAt, Instant updatedAt) {
}
