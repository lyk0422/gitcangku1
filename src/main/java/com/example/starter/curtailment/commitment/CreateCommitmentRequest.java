package com.example.starter.curtailment.commitment;

import java.time.Instant;

/**
 * 创建容量承诺请求。
 *
 * @param commandKey    命令幂等键
 * @param commitmentKey 承诺业务键，全局唯一
 * @param siteId        站点ID
 * @param validFrom     有效区间起点（UTC，含）
 * @param validTo       有效区间终点（UTC，不含）
 * @param maxPowerKw    最大削减功率，十进制字符串，最多 3 位小数，单位 kW
 */
public record CreateCommitmentRequest(String commandKey, String commitmentKey, String siteId,
                                      Instant validFrom, Instant validTo, String maxPowerKw) {
}
