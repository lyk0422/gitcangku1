package com.example.starter.api.dto;

/**
 * 审查保存的一段连续失联区间（响应）。
 *
 * @param fromSegment 起始航段序号（含）
 * @param toSegment   结束航段序号（含）；大于 fromSegment 表示跨段连续失联
 * @param lossMillis  精确失联毫秒（有限小数字符串，由“刻度 × 毫秒”分子除以 10000 得到，不提前取整）
 */
public record LinkGapDto(
        Integer fromSegment,
        Integer toSegment,
        String lossMillis) {
}
