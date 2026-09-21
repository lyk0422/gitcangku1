package com.example.starter.curtailment.commitment;

import java.time.Instant;

/**
 * 容量承诺响应。
 *
 * @param commitmentKey 承诺业务键
 * @param siteId        站点ID
 * @param validFrom     有效区间起点（UTC，含）
 * @param validTo       有效区间终点（UTC，不含）
 * @param maxPowerKw    最大削减功率，十进制字符串，单位 kW
 * @param status        状态：ACTIVE/SUSPENDED
 * @param createdAt     创建时间（UTC）
 */
public record CommitmentResponse(String commitmentKey, String siteId, Instant validFrom, Instant validTo,
                                 String maxPowerKw, String status, Instant createdAt) {
}
