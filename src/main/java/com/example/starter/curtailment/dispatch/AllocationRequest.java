package com.example.starter.curtailment.dispatch;

/**
 * 站点分配请求项。
 *
 * @param siteId  站点ID
 * @param powerKw 分配功率，十进制字符串，最多 3 位小数，大于零，单位 kW
 */
public record AllocationRequest(String siteId, String powerKw) {
}
