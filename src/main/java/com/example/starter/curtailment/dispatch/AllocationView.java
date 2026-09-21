package com.example.starter.curtailment.dispatch;

/**
 * 站点分配视图。
 *
 * @param siteId  站点ID
 * @param powerKw 分配功率，十进制字符串，单位 kW
 */
public record AllocationView(String siteId, String powerKw) {
}
