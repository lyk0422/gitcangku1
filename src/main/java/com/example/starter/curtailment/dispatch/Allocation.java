package com.example.starter.curtailment.dispatch;

import java.math.BigDecimal;

/**
 * 调度站点分配。取消调度后记录仍保留作为历史。
 *
 * @param id         主键
 * @param dispatchId 所属调度ID
 * @param siteId     站点ID
 * @param powerKw    分配功率，单位 kW，最多 3 位小数，大于零
 */
public record Allocation(long id, long dispatchId, String siteId, BigDecimal powerKw) {
}
