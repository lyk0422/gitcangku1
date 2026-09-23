package com.example.starter.api.dto;

/**
 * 可选有效时间窗口，UTC 毫秒时刻，区间左闭右开。
 *
 * <p>起止必须成对出现：均为 {@code null} 或缺省表示全时有效；
 * 成对出现时开始必须严格早于结束（跨字段合法性由业务层校验，非法返回 400）。</p>
 *
 * @param startUtcMillis 窗口起点（含），UTC 毫秒；与 endUtcMillis 同时为 null 表示全时
 * @param endUtcMillis   窗口终点（不含），UTC 毫秒
 */
public record TimeWindowDto(Long startUtcMillis, Long endUtcMillis) {
}
